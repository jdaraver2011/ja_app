package com.example.ja

import android.util.Log
import android.net.Uri
import android.app.Activity
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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

// One extension installation per runtime, not once per episode or rotation.
private val autoplayInstallStarted = Collections.newSetFromMap(WeakHashMap<GeckoRuntime, Boolean>())

private fun installExperimentalAutoplay(runtime: GeckoRuntime) {
    synchronized(autoplayInstallStarted) {
        if (!autoplayInstallStarted.add(runtime)) return
    }
    try {
        runtime.webExtensionController
            .installBuiltIn("resource://android/assets/ja_autoplay/")
            .accept(
                { Log.i("JA_AUTOPLAY", "Autoplay content script installed") },
                { error -> Log.w("JA_AUTOPLAY", "Autoplay extension unavailable; manual Play remains", error) }
            )
    } catch (error: Exception) {
        Log.w("JA_AUTOPLAY", "Could not install autoplay extension; manual Play remains", error)
    }
}

/** Diagnostic bridge isolated from the verified v1.0.3 Auto-Start extension. */
private class JaRemoteBridge {
    // Keep Android's key handling synchronized when the web player silently
    // hides its controls and the JS navigation mode expires.
    var onControlModeClosed: (() -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ports = mutableSetOf<WebExtension.Port>()
    private var activePort: WebExtension.Port? = null

    fun attach(runtime: GeckoRuntime, session: GeckoSession) {
        try {
            runtime.webExtensionController
                .installBuiltIn("resource://android/assets/ja_tv_remote/")
                .accept({ extension ->
                    if (extension == null) {
                        Log.w("JA_TV_BRIDGE", "BRIDGE_INSTALL_NULL")
                        return@accept
                    }
                    session.webExtensionController.setMessageDelegate(
                        extension,
                        object : WebExtension.MessageDelegate {
                            override fun onConnect(port: WebExtension.Port) {
                                ports.add(port)
                                Log.i("JA_TV_BRIDGE", "FRAME_CONNECTED ports=${ports.size}")
                                port.setDelegate(object : WebExtension.PortDelegate {
                                    override fun onPortMessage(message: Any, port: WebExtension.Port) {
                                        Log.i("JA_TV_BRIDGE", "FRAME_REPORT $message")
                                        try {
                                            val report = message as? JSONObject
                                            val event = report?.optString("event")
                                            if (event == "CONTROL_MODE_CLOSED" && activePort === port) {
                                                mainHandler.post { onControlModeClosed?.invoke() }
                                            }
                                            if (event == "ACTION_OK" || event == "CONTROLS_FOUND" || event == "CONTROL_SELECTED" || event == "CONTROL_ACTIVATED") {
                                                if (event != "CONTROLS_FOUND" || (report?.optInt("count", 0) ?: 0) > 0) activePort = port
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
                }, { error -> Log.e("JA_TV_BRIDGE", "BRIDGE_INSTALL_FAILED", error) })
        } catch (e: Exception) {
            Log.e("JA_TV_BRIDGE", "BRIDGE_SETUP_FAILED", e)
        }
    }

    fun send(action: String) {
        if (ports.isEmpty()) {
            Log.w("JA_TV_BRIDGE", "NO_FRAME_PORT action=$action")
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

    fun clear() { ports.clear(); activePort = null; onControlModeClosed = null }
}

/**
 * Independent, read-only media status channel. The verified auto-start and TV
 * remote-control extensions are not changed. A confirmed fatal playback error
 * requests the next provider; an ordinary slow load does not.
 */
private class JaSourceStatusBridge {
    var onStarted: (() -> Unit)? = null
    var onUnavailable: ((String) -> Unit)? = null
    private var connected = true
    private var started = false
    private var unavailable = false
    private val handler = Handler(Looper.getMainLooper())
    private var readyNotified = false

    // A cold GeckoRuntime can take time to install an extension. Only load the
    // embed after its status listener is registered, otherwise a fast player
    // can start before its VIDEO_STARTED event can ever be observed.
    // Installation failure must NOT block playback: proceed without monitoring.
    private fun notifyReady(onReady: () -> Unit) {
        handler.post {
            if (!connected || readyNotified) return@post
            readyNotified = true
            onReady()
        }
    }

    fun attach(runtime: GeckoRuntime, session: GeckoSession, onReady: () -> Unit) {
        try {
            runtime.webExtensionController
                .installBuiltIn("resource://android/assets/ja_source_status/")
                .accept({ extension ->
                    if (!connected) return@accept
                    try {
                        if (extension != null) {
                            session.webExtensionController.setMessageDelegate(
                                extension,
                                object : WebExtension.MessageDelegate {
                                    override fun onConnect(port: WebExtension.Port) {
                                        if (!connected) return
                                        Log.i("JA_SOURCES", "SOURCE_STATUS_PORT_CONNECTED")
                                        port.setDelegate(object : WebExtension.PortDelegate {
                                            override fun onPortMessage(message: Any, port: WebExtension.Port) {
                                                val report = message as? JSONObject ?: return
                                                val event = report.optString("event")
                                                val reason = report.optString("reason")
                                                if (event !in setOf("VIDEO_STARTED", "MEDIA_UNAVAILABLE")) return
                                                handler.post {
                                                    if (!connected) return@post
                                                    when (event) {
                                                        "VIDEO_STARTED" -> if (!started) {
                                                            started = true
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
                        } else {
                            Log.w("JA_SOURCES", "SOURCE_MONITOR_INSTALL_RETURNED_NULL")
                        }
                    } catch (error: Exception) {
                        Log.w("JA_SOURCES", "SOURCE_MONITOR_DELEGATE_FAILED", error)
                    } finally {
                        notifyReady(onReady)
                    }
                }, { error ->
                    Log.w("JA_SOURCES", "SOURCE_MONITOR_UNAVAILABLE", error)
                    notifyReady(onReady)
                })
        } catch (error: Exception) {
            Log.w("JA_SOURCES", "SOURCE_MONITOR_FAILED", error)
            notifyReady(onReady)
        }
    }

    fun clear() {
        connected = false
        onStarted = null
        onUnavailable = null
    }
}

/** JA embedded GeckoView playback. Secondary browser windows are rejected. */
@Composable
fun GeckoExperimentalPlayer(
    url: String,
    showRestore: Boolean = false,
    showDiagnostics: Boolean = false,
    diagnosticsLabel: String = "Embedded source",
    onMediaStarted: () -> Unit = {},
    onFatalError: (String) -> Unit = {},
    // Independent GeckoView-level signal that media is playing. This only
    // suppresses the *unconfirmed* timeout banner: it must NOT mark the
    // selected title as verified, since an embedded page may play an ad.
    onMediaActivity: () -> Unit = {}
) {
    val context = LocalContext.current
    val latestOnMediaStarted by rememberUpdatedState(onMediaStarted)
    val latestOnFatalError by rememberUpdatedState(onFatalError)
    val latestOnMediaActivity by rememberUpdatedState(onMediaActivity)
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
    val remoteBridge = remember(url) { JaRemoteBridge() }
    var webControlMode by remember(url) { mutableStateOf(false) }
    remoteBridge.onControlModeClosed = { webControlMode = false }
    var reload by remember(url) { mutableIntStateOf(0) }
    val sourceStatusBridge = remember(url, reload) { JaSourceStatusBridge() }
    var videoStarted by remember(url, reload) { mutableStateOf(false) }
    var geckoMediaActive by remember(url, reload) { mutableStateOf(false) }
    var lastFatalEvent by remember(url, reload) { mutableStateOf<String?>(null) }
    var status by remember(url, reload) { mutableStateOf("Initializing Firefox/Gecko engine…") }
    var blockedWindows by remember(url, reload) { mutableIntStateOf(0) }
    var blockedPopupRequests by remember(url, reload) { mutableIntStateOf(0) }
    var blockedJsDialogs by remember(url, reload) { mutableIntStateOf(0) }
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
    fun reportFatalOnce(reason: String, detail: String) {
        if (fatalSent || videoStarted) {
            Log.d("JA_SOURCES", "FAILURE_IGNORED reason=$reason started=$videoStarted already=$fatalSent")
            return
        }
        fatalSent = true
        lastFatalEvent = detail
        Log.w("JA_SOURCES", "EMBED_FATAL reason=$reason detail=$detail")
        latestOnFatalError(reason)
    }
    sourceStatusBridge.onStarted = {
        videoStarted = true
        latestOnMediaStarted()
    }
    sourceStatusBridge.onUnavailable = { reason ->
        reportFatalOnce(reason, "Media unavailable: ${reason.take(72)}")
    }
    val session = remember(url, reload) {
        GeckoSession().apply {
            // GeckoView can observe actual media playback even when the
            // source-status script cannot access a nested iframe's <video>.
            // Treat this as media activity, NOT confirmed title availability.
            // Keep explicit error detection / VidFast fallback unchanged.
            setMediaSessionDelegate(object : MediaSession.Delegate {
                override fun onPlay(session: GeckoSession, mediaSession: MediaSession) {
                    geckoMediaActive = true
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
                    if (!JaPlayerPopupPolicy.shouldDismiss(prompt.title, prompt.message)) return null
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
                    if (!JaPlayerPopupPolicy.shouldDismiss(prompt.title, prompt.message)) return null
                    blockedJsDialogs++
                    Log.w("JA_GECKO", "PLAYER_JS_CONFIRM_BLOCKED count=$blockedJsDialogs title=${prompt.title}")
                    // dismiss() means Cancel/false for confirm(), never OK/true.
                    return GeckoResult.fromValue(prompt.dismiss())
                }

                override fun onTextPrompt(
                    session: GeckoSession,
                    prompt: GeckoSession.PromptDelegate.TextPrompt
                ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                    if (!JaPlayerPopupPolicy.shouldDismiss(prompt.title, prompt.message)) return null
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
            // GeckoView 128 uses per-site autoplay permissions, not
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
                // GeckoView 128: AllowOrDeny is org.mozilla.geckoview.AllowOrDeny,
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
                        Log.w("JA_GECKO", "Blocked top-level navigation #$blockedRedirects " +
                            "from=$originalHost to=$lastBlockedHost " +
                            "gesture=${request.hasUserGesture} redirect=${request.isRedirect}")
                        return GeckoResult.fromValue(AllowOrDeny.DENY)
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
                override fun onPageStart(session: GeckoSession, url: String) {
                    pageStarts++
                    lastTopLevelUrl = url
                    lastPageHost = android.net.Uri.parse(url).host ?: "unknown"
                    status = "Loading: $lastPageHost"
                    Log.i("JA_SOURCES", "TOP_LEVEL_PAGE_START host=$lastPageHost")
                }
                override fun onPageStop(session: GeckoSession, success: Boolean) {
                    status = if (success) "Page loaded (video playback not confirmed)" else "Page load failed"
                    if (!success) {
                        Log.w("JA_SOURCES", "TOP_LEVEL_PAGE_STOP_FAILED host=$lastPageHost")
                        reportFatalOnce("EMBED_PAGE_LOAD_FAILED", "Embed page failed to load: $lastPageHost")
                    }
                }
            }
            try {
                val runtime = GeckoRuntime.getDefault(context.applicationContext)
                installExperimentalAutoplay(runtime)
                open(runtime)
                remoteBridge.attach(runtime, this)
                sourceStatusBridge.attach(runtime, this) {
                    // SOURCE_MONITOR_READY must precede this first navigation.
                    // On an extension installation error, notifyReady still
                    // loads the title; monitoring is best-effort, not required.
                    Log.i("JA_SOURCES", "EMBED_LOAD_AFTER_MONITOR_SETUP host=${Uri.parse(url).host}")
                    loadUri(url)
                }
            } catch (e: Exception) {
                status = "GeckoView failed to initialize: ${e.message}"
                Log.e("JA_GECKO", "GeckoView initialization failure", e)
            }
        }
    }
    DisposableEffect(session) {
        onDispose {
            remoteBridge.clear()
            sourceStatusBridge.clear()
            try { session.close() } catch (_: Exception) {}
        }
    }
    Box(Modifier.fillMaxSize()) {
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
                }
            },
            update = { view -> if (view.session !== session) view.setSession(session) }
        )
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

