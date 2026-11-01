package com.example.ja

import android.util.Log
import android.net.Uri
import android.app.Activity
import android.view.View
import android.view.KeyEvent
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
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
                    // JA v1.0.4: map Google TV hardware keys to genuine GeckoView
                    // KeyEvents. No JavaScript injection or extension changes.
                    // NOTE: an embedded player must have keyboard focus and support
                    // the corresponding shortcut for it to have an effect.
                    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                        val tv = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK ==
                            Configuration.UI_MODE_TYPE_TELEVISION
                        if (!tv) return super.dispatchKeyEvent(event)
                        val mapped = when (event.keyCode) {
                            KeyEvent.KEYCODE_DPAD_CENTER,
                            KeyEvent.KEYCODE_ENTER,
                            KeyEvent.KEYCODE_NUMPAD_ENTER,
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                            KeyEvent.KEYCODE_MEDIA_PLAY,
                            KeyEvent.KEYCODE_MEDIA_PAUSE -> KeyEvent.KEYCODE_SPACE
                            KeyEvent.KEYCODE_MEDIA_REWIND -> KeyEvent.KEYCODE_DPAD_LEFT
                            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> KeyEvent.KEYCODE_DPAD_RIGHT
                            else -> event.keyCode
                        }
                        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                            Log.i("JA_TV_REMOTE", "keyCode=${event.keyCode} mapped=$mapped " +
                                "focus=${hasFocus()} source=${event.source} device=${event.deviceId}")
                        }
                        // Allow Android/GeckoView to process Back normally, as in 1.0.3.
                        if (mapped == event.keyCode) return super.dispatchKeyEvent(event)
                        val translated = KeyEvent(
                            event.downTime, event.eventTime, event.action, mapped,
                            event.repeatCount, event.metaState, event.deviceId,
                            0, event.flags, event.source
                        )
                        return super.dispatchKeyEvent(translated)
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

