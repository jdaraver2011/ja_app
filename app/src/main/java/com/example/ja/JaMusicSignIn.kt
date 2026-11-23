package com.example.ja

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
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

/** Keeps Deezer's consent dialog reachable and routes Android TV select keys safely. */
private class DeezerSignInWebView(context: Context) : WebView(context) {
    private var confirmDown = false
    private var initialPageFocusRequested = false

    fun focusWebContentOnce() {
        if (initialPageFocusRequested) return
        initialPageFocusRequested = true
        post { requestFocus() }
    }


    /** When Deezer switches from phone sign-in to its email form, focus the first login field. */
    fun focusLoginFields() {
        val script = """(() => {
            if (window.__jaLoginFieldFocusInstalled) return "observer-installed";
            window.__jaLoginFieldFocusInstalled = true;
            const visible = el => {
                const r = el.getBoundingClientRect();
                const s = getComputedStyle(el);
                return r.width > 0 && r.height > 0 && s.display !== "none" &&
                    s.visibility !== "hidden" && Number(s.opacity || 1) > 0 && !el.disabled;
            };
            const focusLogin = () => {
                const fields = Array.from(document.querySelectorAll("input,textarea,[contenteditable='true']"))
                    .filter(visible);
                const password = fields.find(el => el.tagName === "INPUT" && el.type === "password");
                if (!password) return "no-email-login-form";
                const email = fields.find(el => el.tagName === "INPUT" &&
                    (el.type === "email" || /email|username|user-name/i.test(
                        (el.autocomplete || "") + " " + (el.name || "") + " " + (el.id || "")
                    ))) || fields.find(el => el !== password && el.tagName === "INPUT" &&
                        (!el.type || el.type === "text") && (!password.form || el.form === password.form));
                const target = email || password;
                const active = document.activeElement;
                if (fields.includes(active)) return "login-field-already-focused";
                target.focus({preventScroll:true});
                return email ? "focused-email" : "focused-password";
            };
            const result = focusLogin();
            new MutationObserver(() => focusLogin()).observe(document.documentElement,
                {childList:true, subtree:true});
            return result;
        })()"""
        evaluateJavascript(script) { result ->
            val status = result?.trim('"')?.take(60) ?: "no-result"
            Log.i("JA_DEEZER_REMOTE", "LOGIN_FIELD_FOCUS status=$status")
        }
    }

    fun routePopupIntoThisWindow(resultMsg: android.os.Message?): Boolean {
        val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
        transport.webView = this
        resultMsg.sendToTarget()
        post {
            requestFocus()
            focusWebContentOnce()
            focusLoginFields()
        }
        return true
    }

    fun focusConsentControls() {
        val script = """(() => {
            if (window.__jaConsentFocusInstalled) return "observer-installed";
            window.__jaConsentFocusInstalled = true;
            const isVisible = el => {
                const r = el.getBoundingClientRect();
                const s = window.getComputedStyle(el);
                return r.width > 0 && r.height > 0 && s.display !== "none" &&
                    s.visibility !== "hidden" && Number(s.opacity || 1) > 0;
            };
            const isControl = el => el.matches(
                "button,a[href],[role='button'],[role='link'],input,select,textarea,[tabindex]"
            ) && !el.disabled && el.getAttribute("aria-hidden") !== "true";
            const focusConsentControl = () => {
                const controls = Array.from(document.querySelectorAll(
                    "button,a[href],[role='button'],[role='link'],input,select,textarea,[tabindex]"
                )).filter(el => isVisible(el) && isControl(el));
                for (const control of controls) {
                    let parent = control;
                    for (let depth = 0; parent && depth < 9; depth++, parent = parent.parentElement) {
                        const text = (parent.innerText || parent.textContent || "").toLowerCase();
                        const hasConsentText = text.includes("cookie") &&
                            /(accept|refuse|reject|preferences|consent)/.test(text);
                        if (hasConsentText && text.length < 8000) {
                            const active = document.activeElement;
                            if (active && parent.contains(active)) return "already-focused";
                            control.focus({preventScroll:true});
                            return "focused:" + (control.tagName || "unknown").toLowerCase();
                        }
                    }
                }
                return "no-consent-dialog";
            };
            const result = focusConsentControl();
            const observer = new MutationObserver(() => focusConsentControl());
            observer.observe(document.documentElement, {childList:true, subtree:true, attributes:true});
            return result;
        })()"""
        evaluateJavascript(script) { result ->
            val status = result?.trim('"')?.take(60) ?: "no-result"
            Log.i("JA_DEEZER_REMOTE", "CONSENT_FOCUS status=$status")
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        Log.i("JA_DEEZER_REMOTE", "WEBVIEW_FOCUS gained=$gainFocus direction=$direction window=${hasWindowFocus()}")
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val confirmKeys = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_SPACE
        )
        if (event.keyCode !in confirmKeys) return super.dispatchKeyEvent(event)
        Log.i("JA_DEEZER_REMOTE", "KEY action=${event.action} code=${event.keyCode} repeat=${event.repeatCount} focused=${hasFocus()} window=${hasWindowFocus()}")
        val nativeHandled = super.dispatchKeyEvent(event)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) confirmDown = true
                return true
            }
            KeyEvent.ACTION_UP -> {
                if (confirmDown) activateFocusedControl(event.keyCode, clickIfNeeded = !nativeHandled)
                confirmDown = false
                return true
            }
        }
        return nativeHandled
    }

    private fun activateFocusedControl(keyCode: Int, clickIfNeeded: Boolean) {
        requestFocus()
        val script = """(() => {
            const deepActive = (doc) => {
                let el = doc && (doc.activeElement || doc.querySelector(':focus'));
                for (let depth = 0; el && depth < 8; depth++) {
                    if (el.shadowRoot && el.shadowRoot.activeElement) { el = el.shadowRoot.activeElement; continue; }
                    if (el.tagName === 'IFRAME') {
                        try { const nested = el.contentDocument && (el.contentDocument.activeElement || el.contentDocument.querySelector(':focus')); if (nested) { el = nested; continue; } } catch (_) {}
                    }
                    break;
                }
                return el;
            };
            const el = deepActive(document);
            if (!el) return "none";
            const tag = (el.tagName || "").toLowerCase();
            const type = (el.type || "").toLowerCase();
            const editable = el.isContentEditable || tag === "textarea" ||
                (tag === "input" && !["button","submit","reset","checkbox","radio","file","image","hidden"].includes(type));
            if (editable) {
                el.focus({preventScroll:true});
                return "input:" + tag + ":" + type;
            }
            const clickable = el.matches("button,a,[role='button'],[role='link'],input[type='button'],input[type='submit'],input[type='checkbox'],input[type='radio'],select,[tabindex]");
            if (clickable) {
                el.focus({preventScroll:true});
                if (clickIfNeeded) { el.click(); return "click:" + tag + ":" + type; }
                return "focused-control:" + tag + ":" + type;
            }
            return "no-control:" + tag + ":" + type;
        })()"""
        evaluateJavascript(script) { result ->
            val target = result?.trim('"')?.take(80) ?: "no-result"
            Log.i("JA_DEEZER_REMOTE", "ACTIVATE key=$keyCode target=$target")
            if (target.startsWith("input:")) post {
                requestFocus()
                val input = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                requestFocusFromTouch()
                input?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                Log.i("JA_DEEZER_REMOTE", "IME_REQUESTED")
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
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.setSupportMultipleWindows(true)
                    webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(
                            view: WebView?, isDialog: Boolean, isUserGesture: Boolean,
                            resultMsg: android.os.Message?
                        ): Boolean = (view as? DeezerSignInWebView)?.routePopupIntoThisWindow(resultMsg) ?: false
                    }
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(v: WebView?, url: String?) {
                            cookies()
                            Log.i("JA_DEEZER_REMOTE", "PAGE_READY host=${android.net.Uri.parse(url.orEmpty()).host ?: "unknown"}")
                            (v as? DeezerSignInWebView)?.apply {
                                focusWebContentOnce()
                                focusConsentControls()
                                focusLoginFields()
                                postDelayed({ focusLoginFields() }, 600)
                                postDelayed({ focusLoginFields() }, 1600)
                            }
                        }
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
