package com.example.ja

import android.annotation.SuppressLint
import android.webkit.*
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

internal object JaMusicVisualization { val pcm = JaMusicPcm() }
/** Offline, fixed-origin renderer. Its only bridge exposes a 1024-sample waveform, never account data. */
internal class MusicWaveBridge {
    private var lastReport = 0L
    @Volatile var active = true
    @Volatile private var closed = false
    fun close() { closed = true; active = false }

    @JavascriptInterface fun report(code: String, frames: Int, peak: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastReport < 4000 && code == "FRAME") return
        lastReport = now
        val safe = code.take(60).replace(Regex("[^A-Za-z0-9_:-]"), "_")
        android.util.Log.i("JA_MUSIC_VIS", "WEB_$safe frames=$frames jsPeak=$peak ${JaMusicVisualization.pcm.diagnostic(now)}")
    }
    @JavascriptInterface fun samples(): String = JaMusicVisualization.pcm.snapshotStereo(SystemClock.elapsedRealtime())
    @JavascriptInterface fun isPlaying(): Boolean = JaMusicLabService.state.value.playing
}
@SuppressLint("SetJavaScriptEnabled")
@Composable internal fun JaMusicVisualizer(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by JaMusicLabService.state.collectAsState()
    val bridge = remember { MusicWaveBridge() }
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
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xDD101923)).padding(8.dp).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            VisualizerIconButton(R.drawable.ja_music_back, "Back to player", onBack)
            VisualizerIconButton(R.drawable.ja_music_previous, "Previous track", { JaMusicLabService.previous() })
            VisualizerIconButton(if (state.playing) R.drawable.ja_music_pause else R.drawable.ja_music_play,
                if (state.playing) "Pause" else "Play", { JaMusicLabService.playPause() })
            VisualizerIconButton(R.drawable.ja_music_stop, "Stop music", { JaMusicLabService.stop(context) })
            VisualizerIconButton(R.drawable.ja_music_next, "Next track", { JaMusicLabService.next() })
            VisualizerIconButton(R.drawable.ja_music_visual_presets, "Next visual preset",
                { view.evaluateJavascript("window.nextPreset && window.nextPreset()", null) })
        }
    }
}

@Composable private fun VisualizerIconButton(icon: Int, description: String, onClick: () -> Unit) {
    TvFocusButton(onClick = onClick) {
        Image(painter = painterResource(icon), contentDescription = description, modifier = Modifier.size(24.dp))
    }
}
