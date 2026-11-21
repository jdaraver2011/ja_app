package com.example.ja

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

/** Official provider sign-in. No password field, JavaScript bridge or credential logging in JA. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun JaMusicSignIn(onClose: () -> Unit, onSession: (String) -> Unit) {
    val context = LocalContext.current
    var view by remember { mutableStateOf<WebView?>(null) }
    var captured by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Sign in on Deezer’s secure page. JA saves the session on this device.") }
    fun capture(value: String?) {
        if (!captured && value != null && Regex("[A-Za-z0-9_-]{64,512}").matches(value)) {
            captured = true; onSession(value)
        }
    }
    fun cookies() {
        listOf("https://www.deezer.com", "https://deezer.com").forEach { origin ->
            CookieManager.getInstance().getCookie(origin)?.split(';')?.firstOrNull { it.trim().startsWith("arl=") }
                ?.trim()?.substringAfter('=')?.let { capture(it) }
        }
    }
    LaunchedEffect(Unit) { while (!captured) { delay(1500); cookies() } }
    DisposableEffect(Unit) { onDispose { view?.stopLoading(); view?.destroy(); view = null } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false,
        securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn)) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvFocusButton(onClick = onClose) { Text("Close") }
                TvFocusButton(onClick = { cookies() }) { Text("Finish sign-in") }
            }
            Text(message, color = Color.White)
            AndroidView(modifier = Modifier.fillMaxWidth().weight(1f), factory = {
                WebView(context).apply {
                    view = this
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(v: WebView?, url: String?) { cookies() }
                        override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest): Boolean {
                            val uri = request.url
                            if (uri.scheme == "deezer" && uri.host == "autolog") {
                                capture(uri.lastPathSegment); return true
                            }
                            val allowed = uri.scheme == "https" && (uri.host == "deezer.com" || uri.host?.endsWith(".deezer.com") == true)
                            if (!allowed) message = "Continue with email sign-in on Deezer. App-install links and external sign-in redirects stay closed."
                            return !allowed
                        }
                    }
                    loadUrl("https://www.deezer.com/desktop/login/electron/callback")
                }
            })
        }
    }
}
