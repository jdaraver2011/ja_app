package com.example.ja

import android.util.Log
import android.net.Uri
import android.app.Activity
import android.content.res.Configuration
import android.view.View
import android.view.KeyEvent
import android.os.Handler
import android.os.Looper
import org.mozilla.geckoview.WebExtension
import org.json.JSONObject
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.MediaSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebRequestError
import java.util.Collections
import java.util.WeakHashMap

/**
 * Built-in extensions are runtime-wide, whereas their native-message delegates
 * are registered on each GeckoSession. Installing the same extension again for
 * every source switch can disable the older copy and interrupt content scripts
 * in sessions that are still being torn down. Cache the *installation result*
 * per runtime and URI; each new session still attaches its own delegate.
 *
 * Install once per runtime, then attach each session's native delegates.
 */
private val sharedExtensionInstalls =
    Collections.synchronizedMap(WeakHashMap<GeckoRuntime, MutableMap<String, GeckoResult<WebExtension>>>())

private fun installedOnce(runtime: GeckoRuntime, uri: String): GeckoResult<WebExtension> {
    val install: GeckoResult<WebExtension> = synchronized(sharedExtensionInstalls) {
        val byUri = sharedExtensionInstalls.getOrPut(runtime) { mutableMapOf() }
        byUri.getOrPut(uri) {
            Log.i("JA_GECKO", "SHARED_EXTENSION_INSTALL uri=$uri")
            runtime.webExtensionController.installBuiltIn(uri)
        }
    }
    // A failed installation should be retried on a future player session,
    // not cached permanently as a rejected promise.
    install.accept(
        { },
        { error ->
            synchronized(sharedExtensionInstalls) {
                val byUri = sharedExtensionInstalls[runtime]
                if (byUri?.get(uri) === install) byUri.remove(uri)
            }
            Log.w("JA_GECKO", "SHARED_EXTENSION_INSTALL_FAILED uri=$uri", error)
        }
    )
    return install
}

/** Prewarm and await the same cached installations used by actual playback. */
internal fun prepareJaPlayerExtensions(runtime: GeckoRuntime, done: (Boolean) -> Unit) {
    val uris = arrayOf(
        "resource://android/assets/ja_autoplay/",
        "resource://android/assets/ja_tv_remote/",
        "resource://android/assets/ja_overlay_guard/",
        "resource://android/assets/ja_source_status/"
    )
    val barrier = JaStartupBarrier(object : JaStartupBarrier.Listener {
        override fun onReady() { done(true) }
        override fun onFailure(stage: String, reason: String) {
            Log.w("JA_STARTUP", "EXTENSION_FAILED stage=$stage reason=$reason")
            done(false)
        }
    }, *uris)
    for (uri in uris) {
        try {
            installedOnce(runtime, uri).accept({ extension ->
                if (extension == null) barrier.fail(uri, "Installation returned no extension")
                else {
                    Log.i("JA_STARTUP", "EXTENSION_READY uri=$uri")
                    barrier.complete(uri)
                }
            }, { error -> barrier.fail(uri, error?.message ?: "Installation failed") })
        } catch (error: Exception) {
            barrier.fail(uri, error.message ?: "Installation failed")
        }
    }
}

/** Diagnostic bridge isolated from the verified v1.0.3 Auto-Start extension. */
private class JaRemoteBridge {
    // Keep Android's key handling synchronized when the web player silently
    // hides its controls and the JS navigation mode expires.
    var onControlModeClosed: (() -> Unit)? = null
    // Read-only reports from the actual video frame (not ad/tracker frames).
    var onVideoStatistics: ((Int, Double, Int, Int) -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ports = mutableSetOf<WebExtension.Port>()
    private var activePort: WebExtension.Port? = null
    private var hideTitlePending = false

    fun attach(runtime: GeckoRuntime, session: GeckoSession, onReady: (Boolean) -> Unit) {
        try {
            installedOnce(runtime, "resource://android/assets/ja_tv_remote/")
                .accept({ extension ->
                    if (extension == null) {
                        Log.w("JA_TV_BRIDGE", "BRIDGE_INSTALL_NULL")
                        onReady(false)
                        return@accept
                    }
                    session.webExtensionController.setMessageDelegate(
                        extension,
                        object : WebExtension.MessageDelegate {
                            override fun onConnect(port: WebExtension.Port) {
                                ports.add(port)
                                Log.i("JA_TV_BRIDGE", "FRAME_CONNECTED ports=${ports.size} bridge=${System.identityHashCode(this@JaRemoteBridge)}")
                                if (hideTitlePending) {
                                    try { port.postMessage(JSONObject().put("action", "hideTitleChrome")) }
                                    catch (_: Exception) { }
                                }
                                port.setDelegate(object : WebExtension.PortDelegate {
                                    override fun onPortMessage(message: Any, port: WebExtension.Port) {
                                        Log.i("JA_TV_BRIDGE", "FRAME_REPORT $message")
                                        try {
                                            val report = message as? JSONObject
                                            val event = report?.optString("event")
                                            if (event == "VIDEO_STATS" && report != null &&
                                                report.optInt("width", 0) >= 480 &&
                                                report.optInt("height", 0) >= 270 &&
                                                report.optInt("total", 0) > 0
                                            ) {
                                                val frames = report.optInt("total", 0)
                                                val seconds = report.optDouble("position", 0.0)
                                                if (seconds > 0.0 && seconds.isFinite()) {
                                                    val width = report.optInt("width", 0)
                                                    val height = report.optInt("height", 0)
                                                    mainHandler.post { onVideoStatistics?.invoke(frames, seconds, width, height) }
                                                }
                                            }
                                            if (event == "CONTROL_MODE_CLOSED" && activePort === port) {
                                                mainHandler.post { onControlModeClosed?.invoke() }
                                            }
                                            if (event == "ACTION_OK" || event == "CONTROLS_FOUND" || event == "CONTROL_SELECTED" || event == "CONTROL_ACTIVATED" || event == "VIDEO_STATS") {
                                                val confirmedVideoFrame = event != "VIDEO_STATS" ||
                                                    ((report?.optInt("width", 0) ?: 0) >= 480 &&
                                                     (report?.optInt("height", 0) ?: 0) >= 270)
                                                if (confirmedVideoFrame && (event != "CONTROLS_FOUND" ||
                                                        (report?.optInt("count", 0) ?: 0) > 0)) activePort = port
                                            }
                                        } catch (_: Exception) {}
                                    }
                                    override fun onDisconnect(port: WebExtension.Port) {
                                        ports.remove(port)
                                        if (activePort === port) activePort = null
                                        Log.i("JA_TV_BRIDGE", "FRAME_DISCONNECTED ports=${ports.size}")
                                    }
                                })
                            }
                        }, "ja_tv_remote"
                    )
                    Log.i("JA_TV_BRIDGE", "BRIDGE_INSTALLED_DELEGATE_READY")
                    onReady(true)
                }, { error ->
                    Log.e("JA_TV_BRIDGE", "BRIDGE_INSTALL_FAILED", error)
                    onReady(false)
                })
        } catch (e: Exception) {
            Log.e("JA_TV_BRIDGE", "BRIDGE_SETUP_FAILED", e)
            onReady(false)
        }
    }

    fun send(action: String) {
        if (action == "hideTitleChrome") hideTitlePending = true
        if (ports.isEmpty()) {
            Log.w("JA_TV_BRIDGE", "NO_FRAME_PORT action=$action bridge=${System.identityHashCode(this)}")
            return
        }
        // Initial discovery broadcasts once. Subsequent actions target the confirmed video frame.
        val targets = activePort?.let { if (ports.contains(it)) listOf(it) else ports.toList() } ?: ports.toList()
        Log.i("JA_TV_BRIDGE", "REMOTE_COMMAND action=$action ports=${targets.size} targeted=${activePort != null}")
        for (port in targets) {
            try {
                port.postMessage(JSONObject().put("action", action))
            } catch (e: Exception) {
                Log.w("JA_TV_BRIDGE", "PORT_SEND_FAILED", e)
                ports.remove(port)
                if (activePort === port) activePort = null
            }
        }
    }

    fun clear() { ports.clear(); activePort = null; hideTitlePending = false; onControlModeClosed = null; onVideoStatistics = null }
}

/**
 * Independent, read-only media status channel. The verified auto-start and TV
 * remote-control extensions are not changed. A confirmed fatal playback error
 * requests the next provider; an ordinary slow load does not.
 */
private class JaSourceStatusBridge {
    var resumePositionMs: Long = 0L
    var onProgress: ((Long, Long) -> Unit)? = null
    var onStalled: (() -> Unit)? = null
    var onStarted: (() -> Unit)? = null
    var onUnavailable: ((String) -> Unit)? = null
    private var connected = true
    private var started = false
    private var unavailable = false
    private val handler = Handler(Looper.getMainLooper())
    private var startedPort: WebExtension.Port? = null
    private var readyNotified = false

    // A cold GeckoRuntime can take time to install an extension. Only load the
    // embed after its status listener is registered, otherwise a fast player
    // can start before its VIDEO_STARTED event can ever be observed.
    // Failed initialization reports recovery instead of loading an unmonitored page.
    private fun notifyReady(onReady: (Boolean) -> Unit, success: Boolean) {
        handler.post {
            if (!connected || readyNotified) return@post
            readyNotified = true
            onReady(success)
        }
    }

    fun attach(runtime: GeckoRuntime, session: GeckoSession, onReady: (Boolean) -> Unit) {
        try {
            installedOnce(runtime, "resource://android/assets/ja_source_status/")
                .accept({ extension ->
                    if (!connected) return@accept
                    var delegateReady = false
                    try {
                        if (extension != null) {
                            session.webExtensionController.setMessageDelegate(
                                extension,
                                object : WebExtension.MessageDelegate {
                                    override fun onConnect(port: WebExtension.Port) {
                                        if (!connected) return
                                        Log.i("JA_SOURCES", "SOURCE_STATUS_PORT_CONNECTED")
                                        try { port.postMessage(JSONObject().put("action", "resume").put("positionMs", resumePositionMs)) }
                                        catch (_: Exception) { }
                                        port.setDelegate(object : WebExtension.PortDelegate {
                                            override fun onPortMessage(message: Any, port: WebExtension.Port) {
                                                val report = message as? JSONObject ?: return
                                                val event = report.optString("event")
                                                val reason = report.optString("reason")
                                                if (event !in setOf("VIDEO_STARTED", "MEDIA_UNAVAILABLE", "PROGRESS", "STALLED")) return
                                                handler.post {
                                                    if (!connected) return@post
                                                    if (startedPort != null && startedPort !== port) return@post
                                                    when (event) {
                                                        "PROGRESS" -> {
                                                            val position = report.optLong("positionMs", -1L)
                                                            if (position >= 0L && started) onProgress?.invoke(position, report.optLong("durationMs", 0L))
                                                        }
                                                        "STALLED" -> if (started) onStalled?.invoke()
                                                        "VIDEO_STARTED" -> if (!started) {
                                                            started = true
                                                            startedPort = port
                                                            Log.i("JA_SOURCES", "VIDEO_STARTED frame=${report.optString("frame")}")
                                                            onStarted?.invoke()
                                                        }
                                                        "MEDIA_UNAVAILABLE" -> if (!started && !unavailable) {
                                                            unavailable = true
                                                            Log.w("JA_SOURCES", "CONFIRMED_MEDIA_ERROR reason=$reason")
                                                            onUnavailable?.invoke(reason)
                                                        }
                                                    }
                                                }
                                            }
                                        })
                                    }
                                }, "ja_source_status"
                            )
                            Log.i("JA_SOURCES", "SOURCE_MONITOR_READY")
                            delegateReady = true
                        } else {
                            Log.w("JA_SOURCES", "SOURCE_MONITOR_INSTALL_RETURNED_NULL")
                        }
                    } catch (error: Exception) {
                        Log.w("JA_SOURCES", "SOURCE_MONITOR_DELEGATE_FAILED", error)
                    } finally {
                        notifyReady(onReady, delegateReady)
                    }
                }, { error ->
                    Log.w("JA_SOURCES", "SOURCE_MONITOR_UNAVAILABLE", error)
                    notifyReady(onReady, false)
                })
        } catch (error: Exception) {
            Log.w("JA_SOURCES", "SOURCE_MONITOR_FAILED", error)
            notifyReady(onReady, false)
        }
    }

    fun clear() {
        connected = false
        onStarted = null
        onUnavailable = null
        onProgress = null
        onStalled = null
    }
}

/**
 * High-confidence visual ad/scam overlays live inside HTML and never invoke
 * GeckoSession.PromptDelegate. Install an isolated WebExtension before loading
 * the embed so that it can dismiss the visible overlay without clicking it.
 * Unlike the autoplay and TV-remote scripts, this script never touches video.
 */
private class JaOverlayGuardBridge {
    var onBlocked: ((String, String) -> Unit)? = null
    var onFrameReady: ((String) -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())
    private var connected = true
    private var readyNotified = false

    private fun ready(onReady: (Boolean) -> Unit, success: Boolean) {
        handler.post {
            if (!connected || readyNotified) return@post
            readyNotified = true
            onReady(success)
        }
    }

    fun attach(runtime: GeckoRuntime, session: GeckoSession, onReady: (Boolean) -> Unit) {
        try {
            installedOnce(runtime, "resource://android/assets/ja_overlay_guard/")
                .accept({ extension ->
                    var delegateReady = false
                    try {
                        if (extension != null) {
                            session.webExtensionController.setMessageDelegate(
                                extension,
                                object : WebExtension.MessageDelegate {
                                    override fun onConnect(port: WebExtension.Port) {
                                        if (!connected) return
                                        port.setDelegate(object : WebExtension.PortDelegate {
                                            override fun onPortMessage(message: Any, port: WebExtension.Port) {
                                                val data = message as? JSONObject ?: return
                                                val event = data.optString("event")
                                                if (event !in setOf("GUARD_FRAME_READY", "OVERLAY_BLOCKED", "OVERLAY_BACKDROP_BLOCKED", "AD_FRAME_BLOCKED")) return
                                                val host = data.optString("host").take(100)
                                                handler.post {
                                                    if (!connected) return@post
                                                    Log.i("JA_AD_GUARD", "$event host=$host detail=${data.optString("detail").take(64)}")
                                                    if (event == "GUARD_FRAME_READY") onFrameReady?.invoke(host)
                                                    else onBlocked?.invoke(event, host)
                                                }
                                            }
                                        })
                                    }
                                }, "ja_overlay_guard"
                            )
                            Log.i("JA_AD_GUARD", "OVERLAY_GUARD_READY")
                            delegateReady = true
                        } else {
                            Log.w("JA_AD_GUARD", "OVERLAY_GUARD_EXTENSION_NULL")
                        }
                    } catch (error: Exception) {
                        Log.w("JA_AD_GUARD", "OVERLAY_GUARD_DELEGATE_FAILED", error)
                    } finally {
                        ready(onReady, delegateReady)
                    }
                }, { error ->
                    Log.w("JA_AD_GUARD", "OVERLAY_GUARD_INSTALL_FAILED", error)
                    ready(onReady, false)
                })
        } catch (error: Exception) {
            Log.w("JA_AD_GUARD", "OVERLAY_GUARD_UNAVAILABLE", error)
            ready(onReady, false)
        }
    }

    fun clear() {
        connected = false
        onBlocked = null
        onFrameReady = null
    }
}

/** JA embedded GeckoView playback. Secondary browser windows are rejected. */
@Composable
fun GeckoExperimentalPlayer(
    url: String,
    resumePositionMs: Long = 0L,
    onProgress: (Long, Long) -> Unit = { _, _ -> },
    onPlaybackStalled: () -> Unit = {},
    showRestore: Boolean = false,
    showDiagnostics: Boolean = false,
    adBlockerMode: String = JaGeckoAdBlockers.UBLOCK,
    diagnosticsLabel: String = "Embedded source",
    // Indicates a source loadUri() is about to begin. Not an assertion of
    // playback, page completion, or video availability.
    onEmbedLoadRequested: () -> Unit = {},
    onMediaStarted: () -> Unit = {},
    onVideoStatistics: (Int, Double, Int, Int) -> Unit = { _, _, _, _ -> },
    frameRateMatcher: JaTvFrameRateMatcher? = null,
    onFatalError: (String) -> Unit = {},
    // Independent GeckoView-level signal that media is playing. This only
    // suppresses the *unconfirmed* timeout banner: it must NOT mark the
    // selected title as verified, since an embedded page may play an ad.
    onMediaActivity: () -> Unit = {},
    onTopLevelRedirect: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val isTelevision = (LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
        Configuration.UI_MODE_TYPE_TELEVISION
    val latestOnProgress by rememberUpdatedState(onProgress)
    val latestOnStalled by rememberUpdatedState(onPlaybackStalled)
    val latestOnMediaStarted by rememberUpdatedState(onMediaStarted)
    val latestOnEmbedLoadRequested by rememberUpdatedState(onEmbedLoadRequested)
    val latestOnVideoStatistics by rememberUpdatedState(onVideoStatistics)
    // Always use the matcher from the current PlayerScreen generation.
    val latestFrameRateMatcher by rememberUpdatedState(frameRateMatcher)
    val latestOnFatalError by rememberUpdatedState(onFatalError)
    val latestOnMediaActivity by rememberUpdatedState(onMediaActivity)
    val latestOnTopLevelRedirect by rememberUpdatedState(onTopLevelRedirect)
    // Hide system chrome while the player is visible; restore it on exit.
    val activity = context as? Activity
    DisposableEffect(activity) {
        val decor = activity?.window?.decorView
        val oldFlags = decor?.systemUiVisibility
        if (decor != null) {
            @Suppress("DEPRECATION")
            decor.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }
        onDispose {
            if (decor != null && oldFlags != null) {
                @Suppress("DEPRECATION")
                decor.systemUiVisibility = oldFlags
            }
        }
    }
    var reload by remember(url) { mutableIntStateOf(0) }
    // Bind the remote bridge to the SAME session generation, including reloads.
    // The AndroidView must be keyed the same way, otherwise its factory-created
    // dispatchKeyEvent handler retains a stale bridge even after Compose updates
    // GeckoView.setSession(). This produced NO_FRAME_PORT despite VIDEO_STATS.
    val remoteBridge = remember(url, reload) { JaRemoteBridge() }
    var webControlMode by remember(url, reload) { mutableStateOf(false) }
    remoteBridge.onControlModeClosed = { webControlMode = false }
    val sourceStatusBridge = remember(url, reload) { JaSourceStatusBridge() }
    sourceStatusBridge.resumePositionMs = resumePositionMs
    sourceStatusBridge.onProgress = { position, duration -> latestOnProgress(position, duration) }
    sourceStatusBridge.onStalled = { latestOnStalled() }
    val overlayGuard = remember(url, reload) { JaOverlayGuardBridge() }
    var videoStarted by remember(url, reload) { mutableStateOf(false) }
    val frameStatsDebugCount = remember(url, reload) { intArrayOf(0) }
    remoteBridge.onVideoStatistics = { frames, seconds, width, height ->
        val confirmed = videoStarted
        frameStatsDebugCount[0]++
        if (frameStatsDebugCount[0] <= 3 || frameStatsDebugCount[0] % 24 == 0) {
            Log.i("JA_FRAME_RATE", "FRAME_STATS_HANDOFF sample=${frameStatsDebugCount[0]} " +
                "videoStarted=$confirmed matcherAttached=${latestFrameRateMatcher != null} " +
                "frames=$frames position=$seconds resolution=${width}x$height " +
                "bridge=${System.identityHashCode(remoteBridge)}")
        }
        // The title's VIDEO_STARTED event is an explicit confirmation from
        // the foreground source. Do not change the display for ads/pre-roll.
        if (confirmed) latestFrameRateMatcher?.observe(frames, seconds, width, height)
        // Preserve other optional statistics consumers; no duplicate matcher call.
        latestOnVideoStatistics(frames, seconds, width, height)
    }
    var embedLoadWasRequested by remember(url, reload) { mutableStateOf(false) }
    var geckoMediaActive by remember(url, reload) { mutableStateOf(false) }
    var lastFatalEvent by remember(url, reload) { mutableStateOf<String?>(null) }
    var status by remember(url, reload) { mutableStateOf("Initializing Firefox/Gecko engine…") }
    var adBlockerStatus by remember(url, reload) { mutableStateOf("Checking ${JaGeckoAdBlockers.label(adBlockerMode)}…") }
    var blockedWindows by remember(url, reload) { mutableIntStateOf(0) }
    var blockedPopupRequests by remember(url, reload) { mutableIntStateOf(0) }
    var blockedJsDialogs by remember(url, reload) { mutableIntStateOf(0) }
    var blockedInPageOverlays by remember(url, reload) { mutableIntStateOf(0) }
    var blockedAdFrames by remember(url, reload) { mutableIntStateOf(0) }
    var overlayGuardFrames by remember(url, reload) { mutableIntStateOf(0) }
    var pageStarts by remember(url, reload) { mutableIntStateOf(0) }
    var blockedRedirects by remember(url, reload) { mutableIntStateOf(0) }
    var lastBlockedHost by remember(url, reload) { mutableStateOf("none") }
    var lastPageHost by remember(url, reload) { mutableStateOf(Uri.parse(url).host ?: "unknown") }
    var restoreCount by remember(url, reload) { mutableIntStateOf(0) }
    // Gecko's onLoadError can report the redirected player document rather
    // than the original embed URL. Track the last top-level navigation so a
    // real browser error page is not mistaken for a harmless ad failure.
    var lastTopLevelUrl by remember(url, reload) { mutableStateOf(url) }
    var fatalSent by remember(url, reload) { mutableStateOf(false) }
    // A top-level page can keep loading trackers or other resources even when
    // the embedded media is viable. A PageStop deadline alone must not mark the
    // source as dead. MainActivity already owns a separate 45-second watchdog
    // for genuinely unconfirmed video starts and handles source fallback.
    val loadWatchdogHandler = remember(url, reload) { Handler(Looper.getMainLooper()) }
    val loadWatchdogTask = remember(url, reload) { arrayOfNulls<Runnable>(1) }
    var pageNavigationPending by remember(url, reload) { mutableStateOf(false) }
    fun cancelNavigationWatchdog() {
        loadWatchdogTask[0]?.let { loadWatchdogHandler.removeCallbacks(it) }
        loadWatchdogTask[0] = null
        pageNavigationPending = false
    }
    fun reportFatalOnce(reason: String, detail: String) {
        if (fatalSent || videoStarted) {
            Log.d("JA_SOURCES", "FAILURE_IGNORED reason=$reason started=$videoStarted already=$fatalSent")
            return
        }
        fatalSent = true
        cancelNavigationWatchdog()
        lastFatalEvent = detail
        Log.w("JA_SOURCES", "EMBED_FATAL reason=$reason detail=$detail")
        latestOnFatalError(reason)
    }
    overlayGuard.onFrameReady = { overlayGuardFrames++ }
    overlayGuard.onBlocked = { event, _ ->
        if (event == "AD_FRAME_BLOCKED") blockedAdFrames++ else blockedInPageOverlays++
    }
    sourceStatusBridge.onStarted = {
        cancelNavigationWatchdog()
        videoStarted = true
        // Title banners are separate from JW control bars. Request hiding
        // only after the real player reports VIDEO_STARTED on Android TV.
        if (isTelevision) {
            remoteBridge.send("hideTitleChrome")
            Log.i("JA_TV_BRIDGE", "TITLE_CHROME_HIDE_REQUESTED")
        }
        latestOnMediaStarted()
    }
    sourceStatusBridge.onUnavailable = { reason ->
        reportFatalOnce(reason, "Media unavailable: ${reason.take(72)}")
    }
    val playerDisposed = remember(url, reload) { java.util.concurrent.atomic.AtomicBoolean(false) }
    val startupHandler = remember(url, reload) { Handler(Looper.getMainLooper()) }
    val startupGate = remember(url, reload) { arrayOfNulls<JaStartupBarrier>(1) }
    val session = remember(url, reload) {
        GeckoSession().apply {
            // GeckoView can observe actual media playback even when the
            // source-status script cannot access a nested iframe's <video>.
            // Treat this as media activity, NOT confirmed title availability.
            // Keep explicit error detection / VidFast fallback unchanged.
            setMediaSessionDelegate(object : MediaSession.Delegate {
                override fun onPlay(session: GeckoSession, mediaSession: MediaSession) {
                    geckoMediaActive = true
                    cancelNavigationWatchdog()
                    Log.i("JA_SOURCES", "GECKO_MEDIA_PLAY host=${Uri.parse(url).host}")
                    latestOnMediaActivity()
                }
            })
            contentDelegate = object : GeckoSession.ContentDelegate {}
            // These are native JavaScript dialogs from embedded iframe content,
            // not new browser windows. The existing onNewSession/new-window
            // guard cannot prevent alert() and confirm() from blocking video.
            promptDelegate = object : GeckoSession.PromptDelegate {
                override fun onAlertPrompt(
                    session: GeckoSession,
                    prompt: GeckoSession.PromptDelegate.AlertPrompt
                ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                    // Playback web pages do not need blocking JavaScript alert,
                    // confirm, or text dialogs. Video controls remain untouched;
                    // permissions, choices, and authentication are separate APIs.
                    blockedJsDialogs++
                    Log.w("JA_GECKO", "PLAYER_JS_ALERT_BLOCKED count=$blockedJsDialogs title=${prompt.title}")
                    // alert() has no negative button. Dismiss releases JS without
                    // granting any permission or clicking an advertisement.
                    return GeckoResult.fromValue(prompt.dismiss())
                }

                override fun onButtonPrompt(
                    session: GeckoSession,
                    prompt: GeckoSession.PromptDelegate.ButtonPrompt
                ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                    // Playback web pages do not need blocking JavaScript alert,
                    // confirm, or text dialogs. Video controls remain untouched;
                    // permissions, choices, and authentication are separate APIs.
                    blockedJsDialogs++
                    Log.w("JA_GECKO", "PLAYER_JS_CONFIRM_BLOCKED count=$blockedJsDialogs title=${prompt.title}")
                    // dismiss() means Cancel/false for confirm(), never OK/true.
                    return GeckoResult.fromValue(prompt.dismiss())
                }

                override fun onTextPrompt(
                    session: GeckoSession,
                    prompt: GeckoSession.PromptDelegate.TextPrompt
                ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                    // Playback web pages do not need blocking JavaScript alert,
                    // confirm, or text dialogs. Video controls remain untouched;
                    // permissions, choices, and authentication are separate APIs.
                    blockedJsDialogs++
                    Log.w("JA_GECKO", "PLAYER_JS_TEXT_PROMPT_BLOCKED count=$blockedJsDialogs title=${prompt.title}")
                    return GeckoResult.fromValue(prompt.dismiss())
                }

                override fun onPopupPrompt(
                    session: GeckoSession,
                    prompt: GeckoSession.PromptDelegate.PopupPrompt
                ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                    // This playback-only GeckoSession intentionally has no child
                    // windows. Reject popup requests explicitly as well as the
                    // already-existing NavigationDelegate.onNewSession guard.
                    blockedPopupRequests++
                    val host = Uri.parse(prompt.targetUri.orEmpty()).host ?: "unknown"
                    Log.w("JA_GECKO", "PLAYER_POPUP_REQUEST_BLOCKED count=$blockedPopupRequests host=$host")
                    return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.DENY))
                }
            }
            // GeckoView uses per-site autoplay permissions, not
            // GeckoRuntimeSettings.Builder.autoplayDefault() (removed in v75).
            // Only grant autoplay; do not grant camera, mic or other permissions.
            permissionDelegate = object : GeckoSession.PermissionDelegate {
                override fun onContentPermissionRequest(
                    session: GeckoSession,
                    perm: GeckoSession.PermissionDelegate.ContentPermission
                ): GeckoResult<Int>? {
                    return when (perm.permission) {
                        GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE,
                        GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE -> {
                            Log.i("JA_GECKO", "Autoplay allowed for ${android.net.Uri.parse(perm.uri).host}")
                            GeckoResult.fromValue(
                                GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                            )
                        }
                        else -> null // Leave all other permission requests to GeckoView.
                    }
                }
            }
            navigationDelegate = object : GeckoSession.NavigationDelegate {
                // onLoadRequest handles top-level loads; subframe loads and media
                // requests are NOT blocked by this callback.
                // GeckoView: AllowOrDeny is org.mozilla.geckoview.AllowOrDeny,
                // and LoadRequest does not expose an isTopLevel property.
                override fun onLoadRequest(
                    session: GeckoSession,
                    request: GeckoSession.NavigationDelegate.LoadRequest
                ): GeckoResult<AllowOrDeny>? {
                    val originalHost = Uri.parse(url).host?.lowercase()?.trimEnd('.')
                    val targetUri = Uri.parse(request.uri)
                    val targetHost = targetUri.host?.lowercase()?.trimEnd('.')
                    val scheme = targetUri.scheme?.lowercase()
                    val sameHost = originalHost != null && targetHost != null &&
                        (targetHost == originalHost || targetHost.endsWith(".$originalHost"))

                    // Keep the selected episode in the top-level player.
                    // Allow app-originated original-URL loads and same-site paths;
                    // reject navigation to unrelated hosts or unsafe schemes.
                    val allowed = sameHost && (scheme == "https" || scheme == "http")
                    if (!allowed) {
                        blockedRedirects++
                        lastBlockedHost = targetHost ?: (scheme ?: "unknown")
                        if ((request.isRedirect || !request.hasUserGesture) &&
                                targetHost != null && scheme == "https") {
                            // Save only the attempted top-level navigation;
                            // the domain ledger validates path/host before use.
                            latestOnTopLevelRedirect(request.uri)
                        }
                        Log.w("JA_GECKO", "Blocked top-level navigation #$blockedRedirects " +
                            "from=$originalHost to=$lastBlockedHost " +
                            "gesture=${request.hasUserGesture} redirect=${request.isRedirect}")
                        return GeckoResult.fromValue(AllowOrDeny.DENY)
                    }
                    if (request.isRedirect) {
                        Log.i("JA_GECKO", "TOP_LEVEL_REDIRECT_ALLOWED host=$targetHost original=$originalHost")
                    }
                    return null // GeckoView continues normally.
                }
                override fun onLoadError(
                    session: GeckoSession,
                    uri: String?,
                    error: WebRequestError
                ): GeckoResult<String>? {
                    // JA previously compared ONLY with the original embed URL.
                    // When an embed redirects to a secondary player (observed:
                    // vidspark.to), a Gecko neterror for that document was
                    // ignored and the app remained on Secure Connection Failed.
                    // Compare the last actual top-level URL as well. Never
                    // interpret arbitrary tracker/subresource errors as fatal.
                    val failedUrl = uri.orEmpty()
                    val failedHost = Uri.parse(failedUrl).host?.lowercase()?.trimEnd('.')
                    val selectedHost = Uri.parse(url).host?.lowercase()?.trimEnd('.')
                    val activeHost = Uri.parse(lastTopLevelUrl).host?.lowercase()?.trimEnd('.')
                    val selectedDocument = failedUrl.isNotBlank() &&
                        failedUrl.substringBefore('?').substringBefore('#') ==
                        url.substringBefore('?').substringBefore('#')
                    val foregroundDocument = failedUrl.isNotBlank() &&
                        failedUrl.substringBefore('#') == lastTopLevelUrl.substringBefore('#')
                    // The observed host is an embedded *player*, not a random
                    // ad host; only definitive network/TLS failures qualify.
                    val playerConnectionError = failedHost == "vidspark.to" &&
                        (error.category == WebRequestError.ERROR_CATEGORY_NETWORK ||
                            error.category == WebRequestError.ERROR_CATEGORY_SECURITY)
                    val actionable = selectedDocument || foregroundDocument || playerConnectionError
                    Log.w("JA_SOURCES", "GECKO_LOAD_ERROR host=$failedHost selected=$selectedHost " +
                        "foreground=$activeHost code=${error.code} category=${error.category} " +
                        "actionable=$actionable started=$videoStarted")
                    if (actionable) {
                        reportFatalOnce(
                            "GECKO_CONNECTION_ERROR",
                            "Connection failed: ${failedHost ?: "unknown"} (code ${error.code})"
                        )
                    }
                    return null
                }
                override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                    blockedWindows++
                    Log.w("JA_GECKO", "Rejected secondary window #$blockedWindows (uri host=${android.net.Uri.parse(uri).host})")
                    return null // No child session: reject secondary window.
                }
            }
            progressDelegate = object : GeckoSession.ProgressDelegate {
                override fun onProgressChange(session: GeckoSession, progress: Int) {
                    Log.d("JA_SOURCES", "PAGE_LOAD_PROGRESS percent=$progress blocker=$adBlockerMode")
                }
                override fun onPageStart(session: GeckoSession, url: String) {
                    pageStarts++
                    lastTopLevelUrl = url
                    lastPageHost = android.net.Uri.parse(url).host ?: "unknown"
                    status = "Loading: $lastPageHost"
                    Log.i("JA_SOURCES", "TOP_LEVEL_PAGE_START host=$lastPageHost")
                    cancelNavigationWatchdog()
                    if (url.startsWith("https://", ignoreCase = true) ||
                        url.startsWith("http://", ignoreCase = true)) {
                        pageNavigationPending = true
                        val stalledHost = lastPageHost
                        val task = Runnable {
                            loadWatchdogTask[0] = null
                            if (pageNavigationPending && !fatalSent && !videoStarted && !geckoMediaActive) {
                                // Diagnostic only: do NOT discard a working
                                // stream because a page failed to fire PageStop.
                                // The MainActivity video-start watchdog remains
                                // responsible for unconfirmed-playback fallback.
                                Log.w("JA_SOURCES", "EMBED_NAVIGATION_STILL_LOADING host=$stalledHost " +
                                    "elapsedMs=30000 pageStop=false mediaStarted=false " +
                                    "action=WAIT_FOR_VIDEO_START_WATCHDOG")
                            }
                        }
                        loadWatchdogTask[0] = task
                        loadWatchdogHandler.postDelayed(task, 30_000L)
                    }
                }
                override fun onPageStop(session: GeckoSession, success: Boolean) {
                    cancelNavigationWatchdog()
                    status = if (success) "Page loaded (video playback not confirmed)" else "Page load failed"
                    if (!success) {
                        Log.w("JA_SOURCES", "TOP_LEVEL_PAGE_STOP_FAILED host=$lastPageHost")
                        reportFatalOnce("EMBED_PAGE_LOAD_FAILED", "Embed page failed to load: $lastPageHost")
                    }
                }
            }
            try {
                val runtime = JaGeckoRuntime.get(context)
                open(runtime)
                val startedAt = android.os.SystemClock.elapsedRealtime()
                val gate = JaStartupBarrier(object : JaStartupBarrier.Listener {
                    override fun onReady() {
                        startupHandler.removeCallbacksAndMessages(null)
                        if (playerDisposed.get()) return
                        Log.i("JA_STARTUP", "PLAYER_READY elapsedMs=${android.os.SystemClock.elapsedRealtime() - startedAt}")
                        try {
                            embedLoadWasRequested = true
                            latestOnEmbedLoadRequested()
                            loadUri(url)
                        } catch (error: Exception) {
                            Log.e("JA_STARTUP", "PLAYER_LOAD_FAILED", error)
                            reportFatalOnce("INITIALIZATION_FAILED", "The player page could not open")
                        }
                    }
                    override fun onFailure(stage: String, reason: String) {
                        startupHandler.removeCallbacksAndMessages(null)
                        if (playerDisposed.get()) return
                        Log.w("JA_STARTUP", "PLAYER_SETUP_FAILED stage=$stage reason=$reason " +
                            "elapsedMs=${android.os.SystemClock.elapsedRealtime() - startedAt}")
                        reportFatalOnce("INITIALIZATION_FAILED", "Player setup failed at $stage")
                    }
                }, "extensions", "blocker", "monitors")
                startupGate[0] = gate
                Log.i("JA_STARTUP", "PLAYER_PREPARING mode=$adBlockerMode")
                // A timeout offers recovery. It NEVER starts the page while a
                // runtime-wide installation is still modifying the engine.
                startupHandler.postDelayed({
                    gate.fail("deadline", "Setup did not finish; pending=${gate.pendingStages()}")
                }, 90_000L)
                prepareJaPlayerExtensions(runtime) { ready ->
                    if (!playerDisposed.get() && !gate.isTerminal()) {
                        if (!ready) gate.fail("extensions", "Built-in extension setup failed")
                        else {
                            gate.complete("extensions")
                            remoteBridge.attach(runtime, this) { remoteReady ->
                                if (!playerDisposed.get() && !gate.isTerminal()) {
                                    if (!remoteReady) gate.fail("remote", "Remote delegate setup failed")
                                    else overlayGuard.attach(runtime, this) { guardReady ->
                                        if (!playerDisposed.get() && !gate.isTerminal()) {
                                            if (!guardReady) gate.fail("overlay", "Overlay delegate setup failed")
                                            else sourceStatusBridge.attach(runtime, this) { statusReady ->
                                                if (!playerDisposed.get() && !gate.isTerminal()) {
                                                    if (statusReady) {
                                                        Log.i("JA_STARTUP", "PLAYER_MONITORS_READY")
                                                        gate.complete("monitors")
                                                    } else gate.fail("status", "Status delegate setup failed")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                JaGeckoAdBlockers.prepare(runtime, adBlockerMode) { result ->
                    if (!playerDisposed.get() && !gate.isTerminal()) {
                        adBlockerStatus = result
                        if (result.startsWith("Started:") || result.startsWith("Off:")) {
                            Log.i("JA_STARTUP", "PLAYER_BLOCKER_READY elapsedMs=${android.os.SystemClock.elapsedRealtime() - startedAt}")
                            gate.complete("blocker")
                        } else gate.fail("blocker", result)
                    }
                }
            } catch (e: Exception) {
                status = "GeckoView failed to initialize: ${e.message}"
                reportFatalOnce("INITIALIZATION_FAILED", "The browser engine could not initialize")
                Log.e("JA_GECKO", "GeckoView initialization failure", e)
            }
        }
    }
    DisposableEffect(session) {
        onDispose {
            playerDisposed.set(true)
            startupGate[0]?.cancel()
            startupGate[0] = null
            startupHandler.removeCallbacksAndMessages(null)
            cancelNavigationWatchdog()
            remoteBridge.clear()
            sourceStatusBridge.clear()
            overlayGuard.clear()
            try { session.close() } catch (_: Exception) {}
        }
    }
    Box(Modifier.fillMaxSize()) {
        // Recreate the VIEW whenever the GeckoSession changes. AndroidView.update
        // alone can swap sessions but cannot update the remoteBridge captured by
        // factory's anonymous GeckoView key handler.
        key(session) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                object : GeckoView(it) {
                    // The web player may auto-hide its toolbar even while native
                    // control mode is still set. Time out *both* sides together
                    // so OK returns to Play/Pause instead of clicking stale Mute.
                    private var controlsIdleTask: Runnable? = null
                    private fun clearControlsIdleTask() {
                        controlsIdleTask?.let { removeCallbacks(it) }
                        controlsIdleTask = null
                    }
                    private fun armControlsIdleTask() {
                        clearControlsIdleTask()
                        val task = Runnable {
                            controlsIdleTask = null
                            if (webControlMode) {
                                webControlMode = false
                                Log.i("JA_TV_REMOTE", "CONTROL_MODE_IDLE_TIMEOUT")
                                remoteBridge.send("controlsClose")
                            }
                        }
                        controlsIdleTask = task
                        postDelayed(task, 20000L)
                    }
                    override fun onDetachedFromWindow() {
                        clearControlsIdleTask()
                        super.onDetachedFromWindow()
                    }
                    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                            // Down enters iframe control-navigation mode. Back first exits that mode.
                            // Normal mode retains the verified v1.0.5 OK and +/-10s actions.
                            val action = if (webControlMode) when (event.keyCode) {
                                KeyEvent.KEYCODE_DPAD_LEFT -> "controlsPrev"
                                KeyEvent.KEYCODE_DPAD_RIGHT -> "controlsNext"
                                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                                KeyEvent.KEYCODE_NUMPAD_ENTER -> "controlsSelect"
                                KeyEvent.KEYCODE_DPAD_UP -> "controlsUp"
                                KeyEvent.KEYCODE_DPAD_DOWN -> "controlsDown"
                                KeyEvent.KEYCODE_BACK -> "controlsClose"
                                else -> null
                            } else when (event.keyCode) {
                                KeyEvent.KEYCODE_DPAD_DOWN -> "controlsOpen"
                                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                                KeyEvent.KEYCODE_NUMPAD_ENTER,
                                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "toggle"
                                KeyEvent.KEYCODE_DPAD_LEFT,
                                KeyEvent.KEYCODE_MEDIA_REWIND -> "back10"
                                KeyEvent.KEYCODE_DPAD_RIGHT,
                                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "forward10"
                                else -> null
                            }
                            if (action != null) {
                                if (action == "controlsOpen") webControlMode = true
                                if (action == "controlsClose") {
                                    webControlMode = false
                                    clearControlsIdleTask()
                                } else if (webControlMode) {
                                    // Refresh on each menu/timeline navigation or selection.
                                    armControlsIdleTask()
                                }
                                Log.i("JA_TV_REMOTE", "keyCode=${event.keyCode} action=$action focus=${hasFocus()}")
                                remoteBridge.send(action)
                                return true
                            }
                        }
                        // Keep Back and other keys under GeckoView/Android control.
                        return super.dispatchKeyEvent(event)
                    }
                }.apply {
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setSession(session)
                    post { Log.i("JA_PERF", "GeckoView window hardware acceleration: $isHardwareAccelerated") }
                    requestFocus()
                    Log.i("JA_TV_BRIDGE", "REMOTE_VIEW_BOUND bridge=${System.identityHashCode(remoteBridge)} session=${System.identityHashCode(session)}")
                }
            },
            update = { view -> if (view.session !== session) view.setSession(session) }
        )
        }
        // A read-only diagnostics overlay. No WebExtension, touch simulation,
        // input interception or changes to the verified remote control bridge.
        if (showDiagnostics) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 8.dp, top = 58.dp)
                    .widthIn(max = 330.dp)
                    .background(Color.Black.copy(alpha = 0.80f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text("JA Player Diagnostics", color = Color(0xFFE5A43B), fontSize = 12.sp)
                Text("Provider: $diagnosticsLabel", color = Color.White, fontSize = 11.sp, maxLines = 1)
                Text("Ad block: $adBlockerStatus", color = Color.LightGray, fontSize = 11.sp, maxLines = 2)
                Text("Host: $lastPageHost", color = Color.White, fontSize = 11.sp, maxLines = 1)
                Text("Page: $status", color = Color.White, fontSize = 11.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                val media = when {
                    videoStarted -> "Video start detected"
                    geckoMediaActive -> "Gecko media active (not title-confirmed)"
                    else -> "No video-start signal yet"
                }
                Text("Media: $media", color = Color.White, fontSize = 11.sp, maxLines = 2)
                Text("Remote: ${if (webControlMode) "Controls navigation" else "Play/seek mode"}",
                    color = Color.White, fontSize = 11.sp)
                Text("Page loads: $pageStarts | Popups blocked: ${blockedWindows + blockedPopupRequests}",
                    color = Color.LightGray, fontSize = 11.sp)
                Text("JS dialogs blocked: $blockedJsDialogs",
                    color = Color.LightGray, fontSize = 11.sp)
                Text("In-page overlays blocked: $blockedInPageOverlays | Ad frames: $blockedAdFrames",
                    color = Color.LightGray, fontSize = 11.sp, maxLines = 1)
                Text("Overlay guard frames: $overlayGuardFrames", color = Color.LightGray, fontSize = 11.sp)
                Text("Redirects blocked: $blockedRedirects" +
                    (if (blockedRedirects > 0) " (last: $lastBlockedHost)" else ""),
                    color = Color.LightGray, fontSize = 11.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                lastFatalEvent?.let {
                    Text("Error: $it", color = Color(0xFFFFB8B8), fontSize = 11.sp, maxLines = 2)
                }
            }
        }
        // Preserve the optional original episode restore button.
        if (showRestore) Column(
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            horizontalAlignment = Alignment.End
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (showRestore) Button(onClick = {
                    restoreCount++
                    Log.i("JA_GECKO", "Restore episode #$restoreCount: $url")
                    status = "Restoring selected episode…"
                    // Reload the original selection inside the SAME GeckoSession.
                    session.loadUri(url)
                }) { Text("Restore Episode", fontSize = 12.sp) }
            }
        }
    }
}

