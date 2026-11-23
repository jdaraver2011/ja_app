package com.example.ja

import android.annotation.SuppressLint
import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
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

/** Routes TV-center/Enter to the focused Deezer web control and opens the IME for focused fields. */
private class DeezerSignInWebView(context: Context) : WebView(context) {
    private var confirmDown = false

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode !in setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER))
            return super.dispatchKeyEvent(event)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) confirmDown = true
                return true
            }
            KeyEvent.ACTION_UP -> {
                if (confirmDown) activateFocusedControl()
                confirmDown = false
                return true
            }
        }
        return true
    }

    private fun activateFocusedControl() {
        requestFocus()
        val script = """(() => {
            const el = document.activeElement;
            if (!el) return "none";
            const tag = (el.tagName || "").toLowerCase();
            const type = (el.type || "").toLowerCase();
            const editable = el.isContentEditable || tag === "textarea" ||
                (tag === "input" && !["button","submit","reset","checkbox","radio","file","image"].includes(type));
            if (editable) { el.focus(); el.click(); return "input"; }
            if (el.matches("button,a,[role='button'],input[type='button'],input[type='submit'],[tabindex]")) {
                el.click(); return "click";
            }
            return "none";
        })()"""
        evaluateJavascript(script) { result ->
            if (result?.trim('"') == "input") post {
                val input = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                input?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }
}

/** Official provider sign-in. No password field, JavaScript bridge or credential logging in JA. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun JaMusicSignIn(onClose: () -> Unit, onSession: (String) -> Unit) {
    val context = LocalContext.current
    var view by remember { mutableStateOf<WebView?>(null) }
    val redirects = remember { JaMusicRules.RedirectGuard() }
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
                DeezerSignInWebView(context).apply {
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
                            val upgrade = JaMusicRules.secureDeezerRedirect(uri.toString())
                            if (upgrade != null && redirects.tryUpgrade(android.os.SystemClock.elapsedRealtime())) {
                                v?.loadUrl(upgrade); return true
                            }
                            val allowed = JaMusicRules.allowed(uri.toString()) && (uri.host == "deezer.com" || uri.host?.endsWith(".deezer.com") == true)
                            if (!allowed) message = "Continue with email sign-in on Deezer. App-install links and external sign-in redirects stay closed."
                            return !allowed
                        }
                    }
                    loadUrl("https://account.deezer.com/en-us/login/")
                }
            })
        }
    }
}
