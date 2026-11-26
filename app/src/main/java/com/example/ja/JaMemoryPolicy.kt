package com.example.ja

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Debug
import android.util.Log
import coil.Coil
import java.util.concurrent.atomic.AtomicBoolean

/** Process-wide memory policy for recreatable UI resources and constrained devices. */
internal object JaMemoryPolicy : ComponentCallbacks2 {
    private const val TAG = "JA_MEMORY"
    private const val CONSTRAINED_DEVICE_RAM_BYTES = 4L * 1024L * 1024L * 1024L

    private val registered = AtomicBoolean(false)

    @Volatile
    private var constrainedDevice = false

    @Volatile
    private var videoPlaybackActive = false

    @Volatile
    private var appContext: Context? = null

    fun install(context: Context) {
        val appContext = context.applicationContext
        if (!registered.compareAndSet(false, true)) return
        this.appContext = appContext

        val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val systemMemory = ActivityManager.MemoryInfo().also { activityManager?.getMemoryInfo(it) }
        constrainedDevice = activityManager?.isLowRamDevice == true ||
            (systemMemory.totalMem > 0L && systemMemory.totalMem <= CONSTRAINED_DEVICE_RAM_BYTES)

        appContext.registerComponentCallbacks(this)
        Log.i(
            TAG,
            "POLICY totalRamMb=${systemMemory.totalMem / (1024L * 1024L)} " +
                "memoryClassMb=${activityManager?.memoryClass ?: -1} " +
                "lowRamDevice=${activityManager?.isLowRamDevice ?: false} " +
                "constrained=$constrainedDevice geckoPrewarm=${!constrainedDevice}"
        )
        logMemorySnapshot(appContext, "APP_CREATE", null)
    }

    fun isConstrainedDevice(): Boolean = constrainedDevice

    /** Drop recreatable poster/image references when constrained devices enter video playback. */
    fun setVideoPlaybackActive(active: Boolean) {
        val changed = videoPlaybackActive != active
        videoPlaybackActive = active
        if (active && changed && constrainedDevice) {
            trimRecreatableImages("VIDEO_PLAYBACK_START", null)
        }
        if (changed) {
            Log.i(TAG, "VIDEO_RESOURCE_POLICY active=$active constrained=$constrainedDevice " +
                "imageCacheTrimmed=${active && constrainedDevice}")
        }
    }

    override fun onTrimMemory(level: Int) {
        // UI_HIDDEN frees decoded images that cannot be on screen. RUNNING_LOW
        // and more severe levels free them while the system is under pressure.
        if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
        ) {
            trimRecreatableImages("TRIM", level)
        }
    }

    override fun onLowMemory() {
        trimRecreatableImages("LOW_MEMORY", null)
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    private fun trimRecreatableImages(reason: String, level: Int?) {
        try {
            val context = appContext ?: return
            Coil.imageLoader(context).memoryCache?.clear()
            Log.i(TAG, "IMAGE_MEMORY_CACHE_CLEARED reason=$reason level=${level ?: -1}")
            logMemorySnapshot(context, reason, level)
        } catch (error: Exception) {
            Log.w(TAG, "IMAGE_MEMORY_CACHE_TRIM_FAILED reason=$reason", error)
        }
    }

    private fun logMemorySnapshot(context: Context, reason: String, level: Int?) {
        try {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val systemMemory = ActivityManager.MemoryInfo().also { manager?.getMemoryInfo(it) }
            val appMemory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
            Log.i(
                TAG,
                "SNAPSHOT reason=$reason level=${level ?: -1} " +
                    "appPssKb=${appMemory.totalPss} dalvikPssKb=${appMemory.dalvikPss} " +
                    "nativePssKb=${appMemory.nativePss} systemAvailMb=${systemMemory.availMem / (1024L * 1024L)} " +
                    "systemLowMemory=${systemMemory.lowMemory}"
            )
        } catch (error: Exception) {
            Log.w(TAG, "MEMORY_SNAPSHOT_FAILED reason=$reason", error)
        }
    }
}
