package com.example.ja

import android.util.Log
import android.net.Uri
import android.app.Activity
import android.view.View
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.layout.Box
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
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/** JA embedded GeckoView playback. Secondary browser windows are rejected. */
@Composable
fun GeckoExperimentalPlayer(url: String) {
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
    var status by remember(url) { mutableStateOf("Initializing Firefox/Gecko engine…") }
    var blockedWindows by remember(url) { mutableIntStateOf(0) }
    var blockedRedirects by remember(url) { mutableIntStateOf(0) }
    // Keep the original streaming site as the top-level page. Subframe and media
    // requests are not restricted, because legitimate video hosts vary.
    val initialHost = remember(url) { Uri.parse(url).host.orEmpty().lowercase() }
    fun sameSite(host: String): Boolean {
        val h = host.lowercase()
        return h == initialHost || (initialHost.isNotBlank() && h.endsWith(".$initialHost"))
    }
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
                override fun onLoadRequest(
                    session: GeckoSession,
                    request: GeckoSession.NavigationDelegate.LoadRequest
                ): GeckoResult<GeckoSession.NavigationDelegate.AllowOrDeny>? {
                    val target = Uri.parse(request.uri)
                    val host = target.host.orEmpty()
                    val isHttp = target.scheme.equals("https", true) || target.scheme.equals("http", true)
                    // Only police top-level cross-site navigation. The player may
                    // legitimately use cross-origin frames, scripts and media.
                    if (request.isTopLevel && isHttp && !sameSite(host)) {
                        blockedRedirects++
                        Log.w("JA_GECKO", "Blocked top-level cross-site navigation: $host (count=$blockedRedirects)")
                        return GeckoResult.fromValue(GeckoSession.NavigationDelegate.AllowOrDeny.DENY)
                    }
                    Log.d("JA_GECKO", "Navigation allowed: $host, direct=${request.isTopLevel}")
                    return GeckoResult.fromValue(GeckoSession.NavigationDelegate.AllowOrDeny.ALLOW)
                }
                override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                    blockedWindows++
                    Log.w("JA_GECKO", "Rejected secondary window #$blockedWindows (uri host=${android.net.Uri.parse(uri).host})")
                    return null // No child session: reject secondary window.
                }
            }
            progressDelegate = object : GeckoSession.ProgressDelegate {
                override fun onPageStart(session: GeckoSession, url: String) {
                    status = "Loading: ${android.net.Uri.parse(url).host ?: "unknown host"}"
                    Log.i("JA_GECKO", "Page started: ${android.net.Uri.parse(url).host}")
                }
                override fun onPageStop(session: GeckoSession, success: Boolean) {
                    status = if (success) "Page loaded (video playback not confirmed)" else "Page load failed"
                    Log.i("JA_GECKO", "Page stopped: success=$success; blockedRedirects=$blockedRedirects; blockedWindows=$blockedWindows")
                }
            }
            try {
                open(GeckoRuntime.getDefault(context.applicationContext))
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
            factory = { GeckoView(it).apply { setSession(session) } },
            update = { view -> if (view.session !== session) view.setSession(session) }
        )
    }
}
