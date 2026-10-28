package com.example.ja

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/** An isolated experiment. Chrome Custom Tabs remains the default player. */
@Composable
fun GeckoExperimentalPlayer(url: String) {
    val context = LocalContext.current
    var reload by remember(url) { mutableIntStateOf(0) }
    var status by remember(url) { mutableStateOf("Initializing Firefox/Gecko engine…") }
    var blockedWindows by remember(url) { mutableIntStateOf(0) }
    val session = remember(url, reload) {
        GeckoSession().apply {
            contentDelegate = object : GeckoSession.ContentDelegate {}
            navigationDelegate = object : GeckoSession.NavigationDelegate {
                override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                    blockedWindows++
                    Log.w("JA_GECKO", "Rejected secondary window #$blockedWindows (uri host=${android.net.Uri.parse(uri).host})")
                    return null // No child session: reject secondary window.
                }
            }
            progressDelegate = object : GeckoSession.ProgressDelegate {
                override fun onPageStart(session: GeckoSession, url: String) {
                    status = "Loading: ${android.net.Uri.parse(url).host ?: "unknown host"}"
                }
                override fun onPageStop(session: GeckoSession, success: Boolean) {
                    status = if (success) "Page loaded (video playback not confirmed)" else "Page load failed"
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
    Column {
        Text("GeckoView: $status", fontSize = 11.sp)
        Text("Secondary windows rejected: $blockedWindows", fontSize = 11.sp)
        Button(onClick = { reload++ }) { Text("Reload GeckoView") }
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(320.dp),
            factory = { GeckoView(it).apply { setSession(session) } },
            update = { view -> if (view.session !== session) view.setSession(session) }
        )
    }
}
