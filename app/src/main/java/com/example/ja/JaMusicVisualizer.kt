package com.example.ja

import android.annotation.SuppressLint
import android.webkit.*
import android.os.SystemClock
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

internal object JaMusicVisualization { val pcm = JaMusicPcm() }
/** Offline, fixed-origin renderer. Its only bridge exposes a 1024-sample waveform, never account data. */
internal class MusicWaveBridge(private val onFrame: (Bitmap, String) -> Unit = { bitmap, _ -> bitmap.recycle() }) {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val pending = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var closed = false
    @Volatile var active = true
    private var lastBitmapLog = 0L
    private var bitmapCount = 0
    fun close() { closed = true; active = false }
    @JavascriptInterface fun present(data: String, preset: String) {
        if (closed || !active || data.length > 1024 * 1024 || !data.startsWith("data:image/jpeg;base64,") || !pending.compareAndSet(false, true)) return
        try {
            val bytes = Base64.decode(data.substringAfter(','), Base64.NO_WRAP)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth !in 1..960 || bounds.outHeight !in 1..540) { pending.set(false); return }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
            if (bitmap == null) { pending.set(false); return }
            main.post {
                try {
                    if (closed || !active) bitmap.recycle() else {
                        onFrame(bitmap, preset.take(160))
                        bitmapCount++
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastBitmapLog >= 5000) {
                            lastBitmapLog = now
                            android.util.Log.i("JA_MUSIC_VIS", "NATIVE_BITMAP frames=$bitmapCount width=${bitmap.width} height=${bitmap.height}")
                        }
                    }
                } finally { pending.set(false) }
            }
        } catch (_: Exception) {
            pending.set(false)
            android.util.Log.w("JA_MUSIC_VIS", "NATIVE_BITMAP_DECODE_FAILED")
        }
    }
    private var lastReport = 0L
    @JavascriptInterface fun report(code: String, frames: Int, peak: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastReport < 4000 && code == "FRAME") return
        lastReport = now
        val safe = code.take(60).replace(Regex("[^A-Za-z0-9_:-]"), "_")
        android.util.Log.i("JA_MUSIC_VIS", "WEB_$safe frames=$frames jsPeak=$peak ${JaMusicVisualization.pcm.diagnostic(now)}")
    }
    @JavascriptInterface fun samples(): String = JaMusicVisualization.pcm.snapshot(SystemClock.elapsedRealtime())
}
@SuppressLint("SetJavaScriptEnabled")
@Composable internal fun JaMusicVisualizer(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by JaMusicLabService.state.collectAsState()
    var nativeFrame by remember { mutableStateOf<Bitmap?>(null) }
    var nativePreset by remember { mutableStateOf("") }
    val bridge = remember { MusicWaveBridge { bitmap, preset -> nativeFrame = bitmap; nativePreset = preset } }
    val view = remember {
        WebView(context).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false; settings.allowContentAccess = false
            settings.domStorageEnabled = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            tag = "ja-music-visualizer"
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR || message.message().contains("shader", true))
                        android.util.Log.w("JA_MUSIC_VIS", "CONSOLE level=${message.messageLevel()} line=${message.lineNumber()} " + message.message().take(600))
                    return true
                }
            }
            addJavascriptInterface(bridge, "JaWave")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?) = true
                override fun onPageFinished(v: WebView?, url: String?) { android.util.Log.i("JA_MUSIC_VIS", "PAGE_READY hardware=${v?.isHardwareAccelerated}") }
                override fun onRenderProcessGone(v: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    android.util.Log.e("JA_MUSIC_VIS", "WEBVIEW_PROCESS_GONE crash=${detail?.didCrash()}"); return false
                }
                override fun shouldInterceptRequest(v: WebView?, request: WebResourceRequest?): WebResourceResponse {
                    val uri = request?.url
                    val name = uri?.lastPathSegment.orEmpty()
                    val allowed = uri?.scheme == "https" && uri.host == "music.ja.invalid" && uri.path == "/$name" && name in setOf("index.html", "visualizer.js", "butterchurn.min.js", "presets.min.js")
                    if (!allowed) return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), java.io.ByteArrayInputStream(byteArrayOf()))
                    return try { WebResourceResponse(if (name.endsWith("html")) "text/html" else "application/javascript", "UTF-8", context.assets.open("music-visualizer/$name")) }
                    catch (_: Exception) { WebResourceResponse("text/plain", "UTF-8", 404, "Unavailable", emptyMap(), java.io.ByteArrayInputStream("Visualizer unavailable".toByteArray())) }
                }
            }
            loadUrl("https://music.ja.invalid/index.html")
        }
    }
    DisposableEffect(view, lifecycle) {
        android.util.Log.i("JA_MUSIC_VIS", "OPEN"); JaMusicVisualization.pcm.enable(true)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) { bridge.active = false; JaMusicVisualization.pcm.enable(false); view.onPause() }
            if (event == Lifecycle.Event.ON_RESUME) { bridge.active = true; JaMusicVisualization.pcm.enable(true); view.onResume() }
        }
        lifecycle.addObserver(observer)
        onDispose { bridge.close(); android.util.Log.i("JA_MUSIC_VIS", "CLOSE"); lifecycle.removeObserver(observer); JaMusicVisualization.pcm.enable(false); view.removeJavascriptInterface("JaWave"); view.stopLoading(); view.destroy() }
    }
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize().padding(bottom = 64.dp))
        nativeFrame?.let { bitmap ->
            Image(bitmap.asImageBitmap(), contentDescription = "Music visualizer",
                modifier = Modifier.fillMaxSize().padding(bottom = 64.dp).background(Color.Black),
                contentScale = ContentScale.Fit)
            Text(nativePreset, color = Color.White, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp))
        }
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xDD101923)).padding(8.dp).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            TvFocusButton(onClick = onBack) { Text("‹ Player") }
            TvFocusButton(onClick = { JaMusicLabService.playPause() }) { Text(if (state.playing) "Pause" else "Play") }
            TvFocusButton(onClick = { view.evaluateJavascript("window.nextPreset && window.nextPreset()", null) }) { Text("Next Visual") }
            Text("MilkDrop · Butterchurn", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
        }
    }
}
