package com.example.ja

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Looper
import android.os.Process
import android.util.Log
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** One configured runtime for prewarm, playback, recovery and blocker tools. */
internal object JaGeckoRuntime {
    const val EXTENSION_SCRIPT_SECONDS = 60
    const val BACKGROUND_SCRIPT_PREF = "dom.max_script_run_time"
    const val EXTENSION_SCRIPT_PREF = "dom.max_ext_content_script_run_time"
    private var instance: GeckoRuntime? = null

    /** Only called after the user confirms the root-level double-Back exit. */
    fun shutdownForExit(onComplete: () -> Unit) {
        val runtime = instance
        if (runtime == null) { onComplete(); return }
        try {
            runtime.setDelegate(object : GeckoRuntime.Delegate {
                override fun onShutdown() { onComplete() }
            })
            runtime.shutdown()
        } catch (error: Exception) {
            Log.w("JA_STARTUP", "EXPLICIT_EXIT_SHUTDOWN_FAILED", error)
            onComplete()
        }
    }

    fun get(context: Context): GeckoRuntime {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Gecko runtime requires the main thread" }
        instance?.let { return it }
        // Apply before Gecko starts, including restored add-on background pages.
        // This private, fixed configuration cannot accept arbitrary user prefs.
        // Extension background pages use the normal content-script budget.
        // Both limits remain finite; chrome watchdog and request suspension are unchanged.
        val config = File(context.applicationContext.filesDir, "ja-gecko-runtime-v7.yaml")
        val contents = "prefs:\n  $EXTENSION_SCRIPT_PREF: $EXTENSION_SCRIPT_SECONDS\n" +
            "  $BACKGROUND_SCRIPT_PREF: $EXTENSION_SCRIPT_SECONDS\n"
        if (!config.exists() || config.readText() != contents) config.writeText(contents)
        if (BuildConfig.DEBUG) logStartupEnvironment(context.applicationContext, config)
        val settings = GeckoRuntimeSettings.Builder()
            .configFilePath(config.absolutePath)
            // Firefox DevTools are available only in the side-by-side debug
            // package; release builds keep remote debugging disabled.
            .remoteDebuggingEnabled(BuildConfig.DEBUG)
            .build()
        if (BuildConfig.DEBUG) Log.i("JA_GECKO_DIAG", "REMOTE_DEBUG_ENABLED true")
        if (BuildConfig.DEBUG) Log.i("JA_GECKO_DIAG", "RUNTIME_CREATE_BEGIN uptimeMs=${android.os.SystemClock.elapsedRealtime()}")
        return try {
            GeckoRuntime.create(context.applicationContext, settings).also {
                instance = it
                Log.i("JA_ADBLOCK", "EXTENSION_SCRIPT_BUDGET_CONFIGURED seconds=$EXTENSION_SCRIPT_SECONDS pageWatchdogSeconds=$EXTENSION_SCRIPT_SECONDS")
                if (BuildConfig.DEBUG) Log.i("JA_GECKO_DIAG", "RUNTIME_CREATE_RETURN uptimeMs=${android.os.SystemClock.elapsedRealtime()}")
            }
        } catch (error: Exception) {
            if (BuildConfig.DEBUG) Log.e("JA_GECKO_DIAG", "RUNTIME_CREATE_THROW type=${error.javaClass.name} message=${error.message}", error)
            throw error
        }
    }

    private fun logStartupEnvironment(context: Context, config: File) {
        val appInfo = context.applicationInfo
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        }.getOrDefault("unknown")
        Log.i("JA_GECKO_DIAG", "APP_ENV package=${context.packageName} version=$version sdk=${Build.VERSION.SDK_INT} " +
            "abis=${Build.SUPPORTED_ABIS.joinToString(",")} process64=${Process.is64Bit()} " +
            "sourceApk=${appInfo.sourceDir} splitCount=${appInfo.splitSourceDirs?.size ?: 0} " +
            "gecko=${BuildConfig.GECKOVIEW_VERSION}")
        runCatching {
            ZipFile(appInfo.sourceDir).use { apk ->
                val entry = apk.getEntry("assets/omni.ja")
                if (entry == null) {
                    Log.e("JA_GECKO_DIAG", "OMNIJAR_APK_ENTRY_MISSING apk=${appInfo.sourceDir}")
                } else {
                    val method = if (entry.method == ZipEntry.STORED) "STORED" else if (entry.method == ZipEntry.DEFLATED) "DEFLATED" else "${entry.method}"
                    Log.i("JA_GECKO_DIAG", "OMNIJAR_APK_ENTRY method=$method size=${entry.size} compressedSize=${entry.compressedSize} crc=${entry.crc}")
                }
            }
        }.onFailure { error ->
            Log.e("JA_GECKO_DIAG", "OMNIJAR_APK_INSPECTION_FAILED type=${error.javaClass.name} message=${error.message}")
        }
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)
        Log.i("JA_GECKO_DIAG", "DEVICE_MEMORY totalMemMb=${memoryInfo.totalMem / 1_048_576L} " +
            "availMemMb=${memoryInfo.availMem / 1_048_576L} lowMemory=${memoryInfo.lowMemory} " +
            "memoryClassMb=${activityManager?.memoryClass ?: -1} lowRam=${activityManager?.isLowRamDevice ?: false} " +
            "runtimeConfigBytes=${config.length()}")
    }
}
