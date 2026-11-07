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
internal class JaTvFrameRateMatcher(private val activity: Activity) {
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

    private var lastFrames = -1
    private var lastPosition = -1.0
    private var intervalCount = 0
    private var requestedMode: Display.Mode? = null
    private var lastMeasuredRate = Double.NaN
    private var attempted = false
    private var closed = false

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

    /** Two adjacent 15-second samples help reject pre-roll, seek jumps, and noisy startup statistics. */
    fun observe(totalFrames: Int, positionSeconds: Double) {
        if (!enabled || closed || attempted || requestedMode != null || totalFrames <= 0 || positionSeconds < 4.0) return
        if (lastFrames < 0) {
            lastFrames = totalFrames
            lastPosition = positionSeconds
            return
        }
        val frameDelta = totalFrames - lastFrames
        val timeDelta = positionSeconds - lastPosition
        lastFrames = totalFrames
        lastPosition = positionSeconds
        // The stats report updates every 15 seconds; dismiss stalled/seeked samples.
        if (timeDelta < 8.0 || timeDelta > 25.0 || frameDelta <= 0) {
            intervalCount = 0
            return
        }
        val measured = frameDelta / timeDelta
        if (measured !in 20.0..61.0) {
            intervalCount = 0
            return
        }
        // Require adjacent rates to agree; a seek or an ad switching videos
        // must not force a display-mode change.
        if (lastMeasuredRate.isFinite() && abs(measured - lastMeasuredRate) > 0.7) {
            intervalCount = 0
        }
        lastMeasuredRate = measured
        intervalCount++
        Log.i(tag, "FRAME_RATE_MEASURED fps=${"%.3f".format(java.util.Locale.US, measured)} samples=$intervalCount")
        if (intervalCount < 2) return
        val target = targetFor(measured) ?: run {
            Log.i(tag, "FRAME_RATE_UNKNOWN fps=$measured; no change")
            return
        }
        attempted = true
        requestMode(target)
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

    private fun requestMode(rate: Float) {
        val display = outputDisplay
        val current = display.mode
        val candidates = display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth &&
                it.physicalHeight == current.physicalHeight &&
                abs(it.refreshRate - rate) < 0.12f
        }
        if (candidates.isEmpty()) {
            Log.i(tag, "FRAME_RATE_UNAVAILABLE requestedHz=$rate resolution=${current.physicalWidth}x${current.physicalHeight}")
            return
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
                "targetHz=${mode.refreshRate} resolution=${mode.physicalWidth}x${mode.physicalHeight}")
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
