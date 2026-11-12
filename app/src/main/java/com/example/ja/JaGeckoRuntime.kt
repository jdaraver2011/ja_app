package com.example.ja

import android.content.Context
import android.os.Looper
import android.util.Log
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import java.io.File

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
        val settings = GeckoRuntimeSettings.Builder().configFilePath(config.absolutePath).build()
        return GeckoRuntime.create(context.applicationContext, settings).also {
            instance = it
            Log.i("JA_ADBLOCK", "EXTENSION_SCRIPT_BUDGET_CONFIGURED seconds=$EXTENSION_SCRIPT_SECONDS pageWatchdogSeconds=$EXTENSION_SCRIPT_SECONDS")
        }
    }
}
