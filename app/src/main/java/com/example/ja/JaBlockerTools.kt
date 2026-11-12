package com.example.ja

import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.mozilla.geckoview.*

/** A separate, scoped session for the signed uBlock extension's own UI. */
@Composable
internal fun JaBlockerTools(pages: JaGeckoAdBlockers.ManagementPages, onClose: () -> Unit) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val origin = remember(pages) { Uri.parse(pages.dashboard).host }
    val session = remember(pages) {
        GeckoSession().apply {
            navigationDelegate = object : GeckoSession.NavigationDelegate {
                override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
                    val uri = Uri.parse(request.uri)
                    val allowed = request.uri == "about:blank" ||
                        (uri.scheme == "moz-extension" && uri.host == origin)
                    return GeckoResult.fromValue(if (allowed) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
                }
                override fun onLoadError(session: GeckoSession, uri: String?, cause: WebRequestError): GeckoResult<String>? {
                    error = "uBlock page could not load (code ${cause.code})."
                    loading = false
                    Log.w("JA_ADBLOCK", "TOOLS_PAGE_ERROR code=${cause.code}")
                    return null
                }
            }
            progressDelegate = object : GeckoSession.ProgressDelegate {
                override fun onPageStart(session: GeckoSession, url: String) { loading = true }
                override fun onPageStop(session: GeckoSession, success: Boolean) {
                    loading = false
                    if (!success) error = "The extension page did not finish loading."
                }
            }
            open(JaGeckoRuntime.get(context))
        }
    }
    DisposableEffect(session) {
        onDispose { session.close() }
    }
    LaunchedEffect(session) { session.loadUri(pages.dashboard) }
    BackHandler { onClose() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onClose) { Text("Back to JA") }
            Button(onClick = { error = null; session.loadUri(pages.dashboard) }) { Text("Dashboard") }
            Button(onClick = { error = null; session.loadUri(pages.logger) }) { Text("Logger") }
            Button(onClick = { error = null; session.reload() }) { Text("Reload") }
        }
        Text("uBlock's own interface; use the remote cursor or a mouse if needed.", Modifier.padding(horizontal = 12.dp))
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, Modifier.padding(12.dp)) }
        AndroidView(
            factory = { GeckoView(it).apply { setSession(session) } },
            modifier = Modifier.fillMaxWidth().weight(1f)
        )
    }
}
