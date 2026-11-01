package com.example.ja

import android.util.Log
import android.net.Uri
import android.app.Activity
import android.view.View
import android.view.KeyEvent
import org.mozilla.geckoview.WebExtension
import org.json.JSONObject
import androidx.compose.foundation.border
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
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

    fun clear() { ports.clear(); activePort = null }
}

/** JA embedded GeckoView playback. Secondary browser windows are rejected. */
@Composable
fun GeckoExperimentalPlayer(url: String, showRestore: Boolean = false) {
    val context = LocalContext.current
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
    var reload by remember(url) { mutableIntStateOf(0) }
    var status = "Initializing Firefox/Gecko engine…"
    var blockedWindows = 0
    var pageStarts = 0
    var blockedRedirects = 0
    var lastBlockedHost = "none"
    var lastPageHost = android.net.Uri.parse(url).host ?: "unknown"
    var restoreCount = 0
    val session = remember(url, reload) {
        GeckoSession().apply {
            contentDelegate = object : GeckoSession.ContentDelegate {}
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
                override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                    blockedWindows++
                    Log.w("JA_GECKO", "Rejected secondary window #$blockedWindows (uri host=${android.net.Uri.parse(uri).host})")
                    return null // No child session: reject secondary window.
                }
            }
            progressDelegate = object : GeckoSession.ProgressDelegate {
                override fun onPageStart(session: GeckoSession, url: String) {
                    pageStarts++
                    lastPageHost = android.net.Uri.parse(url).host ?: "unknown"
                    status = "Loading: $lastPageHost"
                }
                override fun onPageStop(session: GeckoSession, success: Boolean) {
                    status = if (success) "Page loaded (video playback not confirmed)" else "Page load failed"
                }
            }
            try {
                val runtime = GeckoRuntime.getDefault(context.applicationContext)
                installExperimentalAutoplay(runtime)
                open(runtime)
                remoteBridge.attach(runtime, this)
                loadUri(url)
            } catch (e: Exception) {
                status = "GeckoView failed to initialize: ${e.message}"
                Log.e("JA_GECKO", "GeckoView initialization failure", e)
            }
        }
    }
    DisposableEffect(session) {
        onDispose { remoteBridge.clear(); try { session.close() } catch (_: Exception) {} }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                object : GeckoView(it) {
                    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                            // Down enters iframe control-navigation mode. Back first exits that mode.
                            // Normal mode retains the verified v1.0.5 OK and +/-10s actions.
                            val action = if (webControlMode) when (event.keyCode) {
                                KeyEvent.KEYCODE_DPAD_LEFT -> "controlsPrev"
                                KeyEvent.KEYCODE_DPAD_RIGHT -> "controlsNext"
                                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                                KeyEvent.KEYCODE_NUMPAD_ENTER -> "controlsSelect"
                                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_UP -> "controlsClose"
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
                                if (action == "controlsClose") webControlMode = false
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

