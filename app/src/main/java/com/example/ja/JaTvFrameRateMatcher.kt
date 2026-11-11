package com.example.ja

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import kotlin.math.abs

/**
 * TV-only experimental refresh-rate preference for GeckoView embedded videos.
 *
 * Receives frame statistics from the existing JA TV remote WebExtension.
 * Does not depend on guessed episode metadata, modify the Gecko surface, or
 * request modes on phone hardware. A confirmed foreground VIDEO_STARTED signal
 * is required by PlayerScreen before observe() is called.
 *
 * Android may ignore window mode preferences. The actual display.mode is logged
 * after the request; this class never equates a requested mode with an applied one.
 */
class JaTvFrameRateMatcher(
    private val activity: Activity,
    private val preferSourceResolution: Boolean = false
) {
    private val tag = "JA_FRAME_RATE"
    private val handler = Handler(Looper.getMainLooper())
    private val manager = activity.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
    private val enabled =
        (activity.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION
    private val originalModePreference = activity.window.attributes.preferredDisplayModeId
    private val originalRefreshPreference = activity.window.attributes.preferredRefreshRate
    @Suppress("DEPRECATION")
    private val outputDisplay: Display = activity.windowManager.defaultDisplay
    private val displayId = outputDisplay.displayId

    private val sampler = JaFrameRateSampler()
    private var lastVideoWidth = 0
    private var lastVideoHeight = 0
    private var requestedMode: Display.Mode? = null
    private var attempted = false
    private var closed = false
    private var statisticsReceived = 0

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(id: Int) {
            if (id == displayId && !closed && requestedMode != null) logActiveMode("FRAME_RATE_DISPLAY_CHANGED")
        }
    }

    init {
        if (enabled) {
            manager?.registerDisplayListener(listener, handler)
            logActiveMode("FRAME_RATE_INITIAL")
        } else {
            Log.i(tag, "FRAME_RATE_DISABLED device_not_tv")
        }
    }

    /** Accept one-second bridge reports; require two agreeing cadence intervals. */
    fun observe(totalFrames: Int, positionSeconds: Double, videoWidth: Int, videoHeight: Int) {
        statisticsReceived++
        if (statisticsReceived <= 3 || statisticsReceived % 24 == 0) {
            Log.i(tag, "FRAME_STATS_RECEIVED count=$statisticsReceived enabled=$enabled closed=$closed " +
                "attempted=$attempted frames=$totalFrames position=$positionSeconds " +
                "video=${videoWidth}x$videoHeight")
        }
        if (!enabled || closed || attempted || requestedMode != null || totalFrames <= 0 || positionSeconds < 4.0) return
        // Some ad frames have tiny videos; valid foreground media must be HD-sized.
        if (videoWidth < 480 || videoHeight < 270) return
        if (lastVideoWidth != videoWidth || lastVideoHeight != videoHeight) {
            sampler.reset()
            lastVideoWidth = videoWidth
            lastVideoHeight = videoHeight
        }
        val measured = sampler.observe(totalFrames, positionSeconds)
        if (!measured.isFinite()) return
        Log.i(tag, "FRAME_RATE_MEASURED fps=${"%.3f".format(java.util.Locale.US, measured)} stable=true")
        val target = targetFor(measured) ?: run {
            Log.i(tag, "FRAME_RATE_UNKNOWN fps=$measured; no change")
            return
        }
        attempted = true
        requestMode(target, videoWidth, videoHeight)
    }

    private fun targetFor(frameRate: Double): Float? = when {
        // 24.000 and 23.976 are difficult to distinguish in 15-second windows;
        // 23.976 is favored for this known TV film-content test.
        frameRate in 23.5..24.5 -> 23.976f
        frameRate in 24.7..25.3 -> 25.0f
        frameRate in 29.5..30.5 -> 29.97f
        frameRate in 49.5..50.5 -> 50.0f
        frameRate in 59.5..60.5 -> 59.94f
        else -> null
    }

    private fun requestMode(rate: Float, videoWidth: Int, videoHeight: Int) {
        val display = outputDisplay
        val current = display.mode
        // Frame-rate matching is the priority. Never trade 23.976 Hz for 60 Hz
        // just to output 1080p. Only select resolution modes at the same rate.
        val rateMatches = display.supportedModes.filter { abs(it.refreshRate - rate) < 0.12f }
        val nativeMatches = rateMatches.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }
        if (nativeMatches.isEmpty()) {
            Log.i(tag, "FRAME_RATE_UNAVAILABLE requestedHz=$rate resolution=${current.physicalWidth}x${current.physicalHeight}")
            return
        }
        var candidates = nativeMatches
        if (preferSourceResolution) {
            // Video dimensions include letterboxing/cropped widescreen frames.
            // A 1920x800 movie is 1080-class output, not an 800p TV mode.
            val desiredWidth = when {
                videoWidth > 1920 || videoHeight > 1080 -> 3840
                videoWidth > 1280 || videoHeight > 720 -> 1920
                else -> 1280
            }
            val desiredHeight = when (desiredWidth) {
                3840 -> 2160
                1920 -> 1080
                else -> 720
            }
            val matchingResolution = rateMatches.filter {
                it.physicalWidth == desiredWidth && it.physicalHeight == desiredHeight
            }
            if (matchingResolution.isNotEmpty()) {
                candidates = matchingResolution
                Log.i(tag, "FRAME_RESOLUTION_TARGET video=${videoWidth}x$videoHeight " +
                    "target=${desiredWidth}x$desiredHeight targetHz=$rate")
            } else {
                Log.i(tag, "FRAME_RESOLUTION_FALLBACK video=${videoWidth}x$videoHeight " +
                    "preferred=${desiredWidth}x$desiredHeight keeping=${current.physicalWidth}x${current.physicalHeight} " +
                    "targetHz=$rate")
            }
        }
        val mode = candidates.minByOrNull { abs(it.refreshRate - rate) } ?: return
        if (mode.modeId == current.modeId) {
            requestedMode = mode
            Log.i(tag, "FRAME_RATE_ALREADY_MATCHED modeId=${mode.modeId} hz=${mode.refreshRate}")
            return
        }
        try {
            val attrs = activity.window.attributes
            attrs.preferredDisplayModeId = mode.modeId
            activity.window.attributes = attrs
            requestedMode = mode
            Log.i(tag, "FRAME_RATE_REQUESTED previousId=${current.modeId} targetId=${mode.modeId} " +
                "targetHz=${mode.refreshRate} resolution=${mode.physicalWidth}x${mode.physicalHeight} " +
                "resolutionMatching=$preferSourceResolution video=${videoWidth}x$videoHeight")
            handler.postDelayed({ if (!closed) logActiveMode("FRAME_RATE_CHECK") }, 3000L)
        } catch (error: Exception) {
            Log.w(tag, "FRAME_RATE_REQUEST_FAILED targetId=${mode.modeId}", error)
        }
    }

    private fun logActiveMode(label: String) {
        val active = outputDisplay.mode
        val desired = requestedMode
        val state = if (desired != null && active.modeId == desired.modeId) "APPLIED" else "NOT_APPLIED"
        Log.i(tag, "$label actualModeId=${active.modeId} actualHz=${active.refreshRate} " +
            "targetModeId=${desired?.modeId ?: 0} result=$state")
    }

    fun close() {
        if (closed) return
        Log.i(tag, "FRAME_RATE_MATCHER_CLOSED stats=$statisticsReceived " +
            "targetModeId=${requestedMode?.modeId ?: 0}")
        closed = true
        if (enabled) manager?.unregisterDisplayListener(listener)
        if (requestedMode != null) {
            try {
                val attrs = activity.window.attributes
                attrs.preferredDisplayModeId = originalModePreference
                attrs.preferredRefreshRate = originalRefreshPreference
                activity.window.attributes = attrs
                Log.i(tag, "FRAME_RATE_RESTORED previousPreference=$originalModePreference refreshPreference=$originalRefreshPreference")
            } catch (error: Exception) {
                Log.w(tag, "FRAME_RATE_RESTORE_FAILED", error)
            }
        }
    }
}
