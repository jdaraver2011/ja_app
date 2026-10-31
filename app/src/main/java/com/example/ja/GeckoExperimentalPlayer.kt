package com.example.ja

import android.util.Log
import android.net.Uri
import android.app.Activity
import android.view.View
import android.view.KeyEvent
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
import org.mozilla.geckoview.WebExtension
import org.json.JSONObject
import java.util.Collections
import java.util.WeakHashMap

// One extension installation per runtime, not once per episode or rotation.
private val playerPorts = Collections.synchronizedSet(mutableSetOf<WebExtension.Port>())
private fun sendPlayerCommand(action: String) {
    val payload = JSONObject().put("action", action)
    val ports = synchronized(playerPorts) { playerPorts.toList() }
    for (port in ports) {
        try { port.postMessage(payload) } catch (e: Exception) {
            playerPorts.remove(port)
            Log.w("JA_TV", "Player frame port unavailable", e)
        }
    }
}

private val autoplayInstallStarted = Collections.newSetFromMap(WeakHashMap<GeckoRuntime, Boolean>())

private fun installExperimentalAutoplay(runtime: GeckoRuntime) {
    synchronized(autoplayInstallStarted) {
        if (!autoplayInstallStarted.add(runtime)) return
    }
    try {
        runtime.webExtensionController
            .installBuiltIn("resource://android/assets/ja_autoplay/")
            .accept(
                { extension ->
                    extension.setMessageDelegate(object : WebExtension.MessageDelegate {
                        override fun onConnect(port: WebExtension.Port) {
                            playerPorts.add(port)
                            port.setDelegate(object : WebExtension.PortDelegate {
                                override fun onPortDisconnect(port: WebExtension.Port) {
                                    playerPorts.remove(port)
                                }
                            })
                        }
                    }, "jaControls")
                    Log.i("JA_AUTOPLAY", "Autoplay + TV frame controls installed")
                },
                { error -> Log.w("JA_AUTOPLAY", "Autoplay extension unavailable; manual Play remains", error) }
            )
    } catch (error: Exception) {
        Log.w("JA_AUTOPLAY", "Could not install autoplay extension; manual Play remains", error)
    }
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
    var tvControlsVisible by remember(url) { mutableStateOf(false) }
    var tvPlaying by remember(url) { mutableStateOf(true) }
    val tvFirstButton = remember { FocusRequester() }
    LaunchedEffect(tvControlsVisible) {
        if (tvControlsVisible) {
            try { tvFirstButton.requestFocus() } catch (_: Exception) { }
        }
    }
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
                loadUri(url)
            } catch (e: Exception) {
                status = "GeckoView failed to initialize: ${e.message}"
                Log.e("JA_GECKO", "GeckoView initialization failure", e)
            }
        }
    }
    DisposableEffect(session) {
        onDispose { try { session.close() } catch (_: Exception) {} }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                object : GeckoView(it) {
                    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                        if (event.action == KeyEvent.ACTION_DOWN && !tvControlsVisible &&
                            (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                             event.keyCode == KeyEvent.KEYCODE_ENTER ||
                             event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                            tvControlsVisible = true
                            return true
                        }
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
        // Native TV remote overlay. Media commands are forwarded to GeckoView;
        // iframe players may not handle them. No simulated screen taps are used.
        if (tvControlsVisible) {
            Row(
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Back) {
                            tvControlsVisible = false
                            true
                        } else false
                    },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                JaTvControl("-10s", { sendPlayerCommand("back10") })
                JaTvControl(if (tvPlaying) "Pause" else "Play", {
                    sendPlayerCommand("toggle")
                    tvPlaying = !tvPlaying // optimistic label; player may reject a command
                }, firstRequester = tvFirstButton)
                JaTvControl("+10s", { sendPlayerCommand("forward10") })
                JaTvControl("Close", { tvControlsVisible = false })
            }
        }
        // Overlay controls do not change the session or navigate back to the catalog.
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

@Composable
private fun JaTvControl(
    label: String,
    onClick: () -> Unit,
    firstRequester: FocusRequester? = null
) {
    var focused by remember { mutableStateOf(false) }
    var modifier = Modifier
        .onFocusChanged { focused = it.isFocused }
        .border(
            if (focused) 3.dp else 0.dp,
            if (focused) Color.White else Color.Transparent,
            RoundedCornerShape(8.dp)
        )
    if (firstRequester != null) modifier = modifier.focusRequester(firstRequester)
    Button(onClick = onClick, modifier = modifier) {
        Text(label, color = Color.White)
    }
}
