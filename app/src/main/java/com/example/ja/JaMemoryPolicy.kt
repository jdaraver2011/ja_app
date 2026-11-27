package com.example.ja

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Debug
import android.util.Log
import coil.Coil
import coil.ImageLoader
import coil.memory.MemoryCache
import java.util.concurrent.atomic.AtomicBoolean

/** Process-wide policy for recreatable images and constrained playback devices. */
internal object JaMemoryPolicy : ComponentCallbacks2 {
    private const val TAG = "JA_MEMORY"
    private const val CONSTRAINED_DEVICE_RAM_BYTES = 4L * 1024L * 1024L * 1024L
    private val registered = AtomicBoolean(false)
    @Volatile private var constrainedDevice = false
    @Volatile private var videoPlaybackActive = false
    @Volatile private var appContext: Context? = null

    fun install(context: Context) {
        val appContext = context.applicationContext
        if (!registered.compareAndSet(false, true)) return
        this.appContext = appContext
        val manager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val systemMemory = ActivityManager.MemoryInfo().also { manager?.getMemoryInfo(it) }
        constrainedDevice = manager?.isLowRamDevice == true ||
            (systemMemory.totalMem > 0L && systemMemory.totalMem <= CONSTRAINED_DEVICE_RAM_BYTES)

        // Keep decoded artwork from competing without limit with Gecko's native
        // renderer/GPU processes. Playback start still clears recreatable images.
        val cacheFraction = if (constrainedDevice) 0.05 else 0.10
        Coil.setImageLoader(
            ImageLoader.Builder(appContext)
                .memoryCache {
                    MemoryCache.Builder().maxSizePercent(appContext, cacheFraction).build()
                }
                .build()
        )
        appContext.registerComponentCallbacks(this)
        Log.i(TAG, "POLICY totalRamMb=${systemMemory.totalMem / (1024L * 1024L)} " +
            "memoryClassMb=${manager?.memoryClass ?: -1} lowRamDevice=${manager?.isLowRamDevice ?: false} " +
            "constrained=$constrainedDevice geckoPrewarm=${!constrainedDevice} imageCacheFraction=$cacheFraction")
        logMemorySnapshot(appContext, "APP_CREATE", null)
    }

    fun isConstrainedDevice(): Boolean = constrainedDevice

    fun setVideoPlaybackActive(active: Boolean) {
        val changed = videoPlaybackActive != active
        videoPlaybackActive = active
        if (active && changed && constrainedDevice) trimRecreatableImages("VIDEO_PLAYBACK_START", null)
        else if (changed) appContext?.let { logMemorySnapshot(it, if (active) "PLAYBACK_START" else "PLAYBACK_END", null) }
        if (changed) Log.i(TAG, "VIDEO_RESOURCE_POLICY active=$active constrained=$constrainedDevice " +
            "imageCacheTrimmed=${active && constrainedDevice}")
    }

    override fun onTrimMemory(level: Int) {
        if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) trimRecreatableImages("TRIM", level)
    }
    override fun onLowMemory() = trimRecreatableImages("LOW_MEMORY", null)
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

    @Suppress("DEPRECATION")
    private fun logMemorySnapshot(context: Context, reason: String, level: Int?) {
        try {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val systemMemory = ActivityManager.MemoryInfo().also { manager?.getMemoryInfo(it) }
            val ownMemory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
            val processes = manager?.runningAppProcesses.orEmpty()
                .filter { it.uid == context.applicationInfo.uid }
            val pids = processes.map { it.pid }.distinct().toIntArray()
            val familyMemory = if (pids.isNotEmpty()) manager?.getProcessMemoryInfo(pids).orEmpty() else emptyList()
            val familyPss = familyMemory.sumOf { it.totalPss }
            val familySwapPss = familyMemory.sumOf { it.totalSwappablePss }
            Log.i(TAG, "SNAPSHOT reason=$reason level=${level ?: -1} " +
                "mainPssKb=${ownMemory.totalPss} dalvikPssKb=${ownMemory.dalvikPss} nativePssKb=${ownMemory.nativePss} " +
                "visibleProcessCount=${familyMemory.size} processFamilyPssKb=${if (familyMemory.isEmpty()) -1 else familyPss} " +
                "processFamilySwapPssKb=${if (familyMemory.isEmpty()) -1 else familySwapPss} " +
                "systemAvailMb=${systemMemory.availMem / (1024L * 1024L)} systemLowMemory=${systemMemory.lowMemory}")
        } catch (error: Exception) {
            Log.w(TAG, "MEMORY_SNAPSHOT_FAILED reason=$reason", error)
        }
    }
}
