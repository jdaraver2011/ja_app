package com.example.ja

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import android.view.Display

/**
 * One-time read-only device diagnostics for Android TV.
 * A hardware decoder being LISTED does not prove GeckoView selected it.
 * Logs are deliberately limited to codec names, display mode and OS version.
 */
internal object JaTvMediaDiagnostics {
    private const val TAG = "JA_PERF"

    fun logIfTelevision(context: Context, display: Display?) {
        val tvMode = (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)
            ?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        val leanback = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        if (!tvMode && !leanback) return

        try {
            val mode = display?.mode
            Log.i(TAG, "TV_OUTPUT model=${Build.MODEL} api=${Build.VERSION.SDK_INT} " +
                "pixels=${mode?.physicalWidth}x${mode?.physicalHeight} " +
                "refreshHz=${mode?.refreshRate}")
        } catch (e: Exception) {
            Log.w(TAG, "TV_OUTPUT_QUERY_FAILED", e)
        }
        try {
            val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filterNot { it.isEncoder }
            for (mime in listOf("video/avc", "video/hevc", "video/x-vnd.on2.vp9", "video/av01")) {
                val supported = codecs.filter { info ->
                    info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
                }
                val labels = supported.map { info ->
                    val hw = if (Build.VERSION.SDK_INT >= 29) {
                        if (info.isHardwareAccelerated) "HW" else "SW/OTHER"
                    } else "UNKNOWN"
                    "${info.name}($hw)"
                }
                Log.i(TAG, "TV_CODEC_AVAILABLE mime=$mime codecs=${labels.joinToString().take(450)}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "TV_CODEC_QUERY_FAILED", e)
        }
    }
}
