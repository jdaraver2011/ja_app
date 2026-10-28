package com.example.ja

import android.os.Bundle
import androidx.browser.customtabs.CustomTabsIntent
import android.content.Intent
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.net.Uri
import android.graphics.Bitmap
import android.util.Log
import android.webkit.WebView
import android.webkit.WebChromeClient
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.app.Activity
import android.app.Dialog
import android.widget.Button
import android.widget.LinearLayout
import android.webkit.WebViewClient
import android.os.Message
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.room.Room
import coil.compose.AsyncImage
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val bg = Color(0xFF12171D)
private val accent = Color(0xFFE5A43B)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "ja.db").build()
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = accent, background = bg, surface = Color(0xFF202832))) { StreamingApp(db.dao()) } }
    }
}

@Composable fun StreamingApp(dao: WatchDao) {
    val repo = remember { TmdbRepository() }
    val provider = remember { PlaybackRegistry() }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf("Home") }
    var results by remember { mutableStateOf(emptyList<TitleItem>()) }
    var selected by remember { mutableStateOf<TitleItem?>(null) }
    var playback by remember { mutableStateOf<PlaybackSource?>(null) }
    var request by remember { mutableStateOf<PlaybackRequest?>(null) }
    var history by remember { mutableStateOf(emptyList<WatchItem>()) }
    var search by remember { mutableStateOf("") }
    var seasons by remember { mutableStateOf(emptyList<Int>()) }
    var season by remember { mutableIntStateOf(1) }
    var episodes by remember { mutableStateOf(emptyList<EpisodeItem>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var playbackLoading by remember { mutableStateOf(false) }
    fun refreshHistory() { scope.launch { history = dao.all() } }
    fun load(block: suspend () -> List<TitleItem>) { scope.launch { error = null; try { results = block() } catch (e: Exception) { error = e.message } } }
    LaunchedEffect(tab) {
        refreshHistory()
        when (tab) {
            "Home" -> load { repo.trending() }
            "Movies" -> load { repo.movies() }
            "TV" -> load { repo.shows() }
            "Favorites", "Continue" -> {
                results = history.filter { if (tab == "Favorites") it.favorite else it.positionMs > 0 }.distinctBy { it.mediaId to it.mediaType }.map { TitleItem(it.mediaId, it.title, "", it.poster, null, it.mediaType) }
            }
        }
    }
    fun open(item: TitleItem) {
        selected = item; episodes = emptyList(); seasons = emptyList()
        if (item.type == "tv") scope.launch {
            try { seasons = repo.seasons(item.id); season = seasons.firstOrNull() ?: 1; episodes = repo.episodes(item.id, season) } catch (e: Exception) { error = e.message }
        }
    }
    fun play(item: TitleItem, s: Int = 0, e: Int = 0) {
        error = null
        playback = null
        playbackLoading = true
        Log.d("JA_PLAYBACK", "Play clicked: ${item.title} (${item.type}, TMDB ${item.id})")
        scope.launch {
            try {
                // VidSrc-style TMDB embed URLs use the existing numeric TMDB ID.
                val r = PlaybackRequest(item, s, e)
                request = r
                val source = provider.resolve(r)
                if (source == null) {
                    error = "Could not construct a playback URL for this TMDB title."
                    return@launch
                }
                Log.d("JA_PLAYBACK", "Resolved source type: ${source::class.java.simpleName}")
                playback = source
                val old = dao.find(item.id, item.type, s, e)
                dao.save(WatchItem(item.id, item.type, s, e, item.title, item.poster, old?.favorite ?: false, old?.positionMs ?: 0))
                refreshHistory()
            } catch (ex: Exception) {
                error = "Playback lookup failed: ${ex.message ?: "Unknown error"}"
                Log.e("JA_PLAYBACK", "Playback lookup failed", ex)
            } finally {
                playbackLoading = false
            }
        }
    }
    BackHandler(playback != null || selected != null) { if (playback != null) playback = null else selected = null }
    Column(Modifier.fillMaxSize().background(bg).padding(16.dp)) {
        Text("JA", style = MaterialTheme.typography.headlineMedium, color = accent)
        Spacer(Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("Home", "Movies", "TV", "Search", "Favorites", "Continue")) { t ->
                Button(onClick = { tab = t; selected = null; playback = null }, colors = ButtonDefaults.buttonColors(containerColor = if (tab == t) accent else Color.DarkGray)) { Text(t, color = Color.Black) }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (playbackLoading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Looking up playback source…") }
        error?.let { Text(it, color = Color(0xFFFFA8A8)) }
        if (playback != null) {
            Text("Now playing: ${request?.media?.title.orEmpty()}")
            PlayerScreen(playback!!, request, dao, onClose = { playback = null })
        } else if (selected != null) {
            val item = selected!!
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AsyncImage(item.posterUrl, null, Modifier.width(125.dp).height(185.dp), contentScale = ContentScale.Crop)
                Column {
                    Text(item.title, style = MaterialTheme.typography.headlineSmall)
                    Text("TMDB rating: ${"%.1f".format(item.rating)}")
                    Text(item.overview, maxLines = 5, overflow = TextOverflow.Ellipsis)
                    Row {
                        Button(onClick = { if (item.type == "movie") play(item) else play(item, season, episodes.firstOrNull()?.episode ?: 1) }) { Text("Play") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { scope.launch { val old = dao.find(item.id, item.type, 0, 0); dao.save(WatchItem(item.id, item.type, title = item.title, poster = item.poster, favorite = !(old?.favorite ?: false), positionMs = old?.positionMs ?: 0)); refreshHistory() } }) { Text("Toggle favorite") }
                    }
                }
            }
            if (item.type == "tv") {
                Text("Seasons", style = MaterialTheme.typography.titleLarge)
                LazyRow { items(seasons) { s -> Button(onClick = { season = s; scope.launch { try { episodes = repo.episodes(item.id, s) } catch (ex: Exception) { error = ex.message } } }) { Text("Season $s") } } }
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    episodes.forEach { ep -> TextButton(onClick = { play(item, ep.season, ep.episode) }) { Text("S${ep.season} E${ep.episode} • ${ep.name}") } }
                }
            }
        } else {
            if (tab == "Search") {
                OutlinedTextField(value = search, onValueChange = { search = it }, label = { Text("Search TMDB") }, singleLine = true)
                Button(onClick = { if (search.isNotBlank()) load { repo.search(search) } }) { Text("Search") }
            }
            Text(if (tab == "Home") "Trending this week" else tab, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Column(Modifier.verticalScroll(rememberScrollState())) {
                results.chunked(5).forEach { group ->
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 14.dp)) {
                        items(group) { item ->
                            Column(Modifier.width(140.dp).clickable { open(item) }) {
                                AsyncImage(item.posterUrl, item.title, Modifier.fillMaxWidth().height(190.dp).border(1.dp, Color.DarkGray, RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                                Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable fun PlayerScreen(source: PlaybackSource, request: PlaybackRequest?, dao: WatchDao, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var playerError by remember(source) { mutableStateOf<String?>(null) }
    var webStatus by remember(source) { mutableStateOf<String?>(null) }
    var resetCount by remember(source) { mutableIntStateOf(0) }
    var videoStatus by remember(source) { mutableStateOf("Video status: not inspected") }
    var lastConsoleError by remember(source) { mutableStateOf<String?>(null) }
    var showDiagnostics by remember(source) { mutableStateOf(false) }
    var iframeStatus by remember(source) { mutableStateOf("Frames: not inspected") }
    var fullscreenStatus by remember(source) { mutableStateOf("Fullscreen: not requested") }
    var loadCompleted by remember(source) { mutableStateOf(false) }
    var pendingNavigation by remember(source) { mutableStateOf<String?>(null) }
    var navigationApproval by remember(source) { mutableStateOf<String?>(null) }
    var currentMainHost by remember(source) { mutableStateOf<String?>(null) }
    var navigationNote by remember(source) { mutableStateOf("No external navigation detected") }
    val approvedHosts = remember(source) { mutableStateListOf<String>() }
    var originalReloadCount by remember(source) { mutableIntStateOf(0) }
    var compatibilityMode by remember(source) { mutableStateOf(true) }
    var pageProgress by remember(source) { mutableIntStateOf(0) }
    playerError?.let { Text(it, color = Color(0xFFFFA8A8)) }
    webStatus?.let { Text(it, color = Color.LightGray) }
    Button(onClick = onClose) { Text("Back to details") }
    when (source) {
        is PlaybackSource.Native -> {
            val player = remember(source.url) { ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(source.url))
                prepare()
                playWhenReady = true
            } }
            DisposableEffect(player) {
                val listener = object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        playerError = "Native player failed: ${error.message ?: "Unknown media error"}"
                        Log.e("JA_PLAYBACK", "Native playback error", error)
                    }
                }
                player.addListener(listener)
                onDispose { player.removeListener(listener) }
            }
            LaunchedEffect(player, request) {
                val r = request ?: return@LaunchedEffect
                val old = dao.find(r.media.id, r.media.type, r.season, r.episode)
                if (old != null && old.positionMs > 0) player.seekTo(old.positionMs)
            }
            DisposableEffect(player, request) {
                onDispose {
                    val r = request
                    if (r != null) scope.launch { val old = dao.find(r.media.id, r.media.type, r.season, r.episode); dao.save(WatchItem(r.media.id, r.media.type, r.season, r.episode, r.media.title, r.media.poster, old?.favorite ?: false, player.currentPosition.coerceAtLeast(0))) }
                    player.release()
                }
            }
            AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = true } }, modifier = Modifier.fillMaxWidth().height(320.dp))
        }
        is PlaybackSource.Embed -> {
            var useEmbeddedWebView by remember(source.url) { mutableStateOf(false) }
            var customTabMessage by remember(source.url) { mutableStateOf<String?>(null) }
            fun launchChromeTab() {
                try {
                    CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(source.url))
                    customTabMessage = "Chrome-powered tab launched. Close it to return to JA."
                } catch (e: Exception) {
                    customTabMessage = "Could not launch a Custom Tab: ${e.message}. Try external Chrome or embedded WebView."
                }
            }
            // Launch once when a new playback URL is selected; returning to JA never relaunches it.
            LaunchedEffect(source.url) { launchChromeTab() }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Chrome Custom Tab is the default player. It opens over JA; close it to return here.", fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { launchChromeTab() }) { Text("Chrome in JA", fontSize = 12.sp) }
                    Button(onClick = {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(source.url))
                                .addCategory(Intent.CATEGORY_BROWSABLE)
                                .setPackage("com.android.chrome")
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            // If Chrome isn't installed, allow the user to select an external browser.
                            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source.url)).addCategory(Intent.CATEGORY_BROWSABLE)) }
                            catch (other: Exception) { customTabMessage = "No external browser available: ${other.message}" }
                        }
                    }) { Text("External Chrome", fontSize = 12.sp) }
                }
                Button(onClick = { useEmbeddedWebView = !useEmbeddedWebView }) {
                    Text(if (useEmbeddedWebView) "Hide embedded WebView" else "Try embedded WebView")
                }
                customTabMessage?.let { Text(it, fontSize = 11.sp, color = Color.LightGray) }
            }
            if (useEmbeddedWebView) {

            // Embed position cannot be reliably measured; never claim exact resume support.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(source.url))
                    intent.addCategory(Intent.CATEGORY_BROWSABLE)
                    try { context.startActivity(intent) }
                    catch (e: Exception) { playerError = "No browser available: ${e.message}" }
                }) { Text("Open in browser") }
                Button(onClick = { resetCount++; webStatus = "Resetting player…"; videoStatus = "Video status: not inspected"; iframeStatus = "Frames: not inspected"; lastConsoleError = null; loadCompleted = false; pendingNavigation = null; approvedHosts.clear() }) { Text("Reset player") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = compatibilityMode, onCheckedChange = { compatibilityMode = it; resetCount++; pendingNavigation = null; approvedHosts.clear(); loadCompleted = false; webStatus = "Recreating player with updated navigation mode…" })
                Text("Chrome-like compatibility mode (recommended)", fontSize = 12.sp)
            }
            Button(onClick = {
                originalReloadCount++
                pendingNavigation = null
                approvedHosts.clear()
                navigationNote = "Reloaded original playback URL"
                webStatus = "Reloading original player…"
                loadCompleted = false
            }) { Text("Reload original player") }
            if (pendingNavigation != null) {
                val destination = pendingNavigation!!
                val destinationHost = Uri.parse(destination).host ?: "unknown host"
                AlertDialog(
                    onDismissRequest = { pendingNavigation = null },
                    title = { Text("Player leaving original website") },
                    text = { Text("The embedded player requested navigation to $destinationHost. Keep the JA player on its original page, or allow this destination? Some legitimate players require redirects.") },
                    confirmButton = { TextButton(onClick = {
                        if (!approvedHosts.contains(destinationHost)) approvedHosts.add(destinationHost)
                        pendingNavigation = null
                        navigationNote = "Allowed navigation to $destinationHost"
                        // Loading is performed by the existing WebView below, without replacing it.
                        navigationApproval = destination
                    }) { Text("Allow in player") } },
                    dismissButton = { TextButton(onClick = {
                        pendingNavigation = null
                        navigationNote = "Stayed on original player; declined $destinationHost"
                    }) { Text("Stay on player") } }
                )
            }
            val web = remember(source.url, resetCount) { WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                settings.mediaPlaybackRequiresUserGesture = false
                // Keep the standard WebView user agent; spoofing Chrome is not equivalent to Chrome.
                settings.loadsImagesAutomatically = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.javaScriptCanOpenWindowsAutomatically = false
                // User-initiated secondary windows stay separate from the original player.
                settings.setSupportMultipleWindows(true)
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                // Allow HTML5 fullscreen video using a temporary view overlay.
                webChromeClient = object : WebChromeClient() {
                    private val popupDialogs = mutableListOf<Dialog>()
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        pageProgress = newProgress
                        Log.d("JA_PLAYBACK", "Page progress=$newProgress host=${view?.url?.let { Uri.parse(it).host }}")
                    }
                    override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                        if (message != null) {
                            val line = "${message.messageLevel()} ${message.message()} (${message.sourceId()}:${message.lineNumber()})"
                            Log.d("JA_PLAYBACK", "JS console: $line")
                            if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) lastConsoleError = line.take(250)
                        }
                        return true
                    }
                    override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                        if (!isUserGesture || resultMsg == null) {
                            Log.w("JA_PLAYBACK", "Denied non-user-initiated popup")
                            return false
                        }
                        val activity = context as? Activity ?: return false
                        // Show the secondary window explicitly; never load advertising content invisibly.
                        // The original player remains in place and can be tapped again after closing.
                        val dialog = Dialog(activity)
                        val container = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                        val close = Button(activity).apply { text = "Close pop-up and return to JA player" }
                        val child = WebView(activity).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            settings.setSupportMultipleWindows(false)
                            settings.javaScriptCanOpenWindowsAutomatically = false
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?): Boolean {
                                    val uri = request?.url ?: return true
                                    val scheme = uri.scheme?.lowercase()
                                    val allowed = scheme == "https" || scheme == "http" || uri.toString() == "about:blank"
                                    if (!allowed) Log.w("JA_PLAYBACK", "Blocked popup scheme=$scheme")
                                    return !allowed
                                }
                            }
                        }
                        close.setOnClickListener { dialog.dismiss() }
                        container.addView(close, LinearLayout.LayoutParams(-1, -2))
                        container.addView(child, LinearLayout.LayoutParams(-1, 0, 1f))
                        dialog.setContentView(container)
                        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        dialog.setOnDismissListener {
                            popupDialogs.remove(dialog)
                            child.stopLoading()
                            child.destroy()
                            Log.d("JA_PLAYBACK", "Popup closed; original player still available")
                        }
                        popupDialogs.add(dialog)
                        dialog.show()
                        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        val transport = resultMsg.obj as? WebView.WebViewTransport
                        if (transport == null) {
                            dialog.dismiss()
                            return false
                        }
                        transport.webView = child
                        resultMsg.sendToTarget()
                        Log.d("JA_PLAYBACK", "Opened visible secondary popup; original JA player preserved")
                        return true
                    }
                    override fun onCloseWindow(window: WebView?) {
                        popupDialogs.toList().forEach { it.dismiss() }
                    }
                    fun closePopups() {
                        popupDialogs.toList().forEach { it.dismiss() }
                    }
                    private var fullscreenCallback: CustomViewCallback? = null
                    private var fullscreenView: View? = null
                    private var overlay: FrameLayout? = null
                    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                        if (view == null || fullscreenView != null) {
                            callback?.onCustomViewHidden()
                            return
                        }
                        val activity = context as? Activity ?: run {
                            callback?.onCustomViewHidden()
                            return
                        }
                        val frame = FrameLayout(activity)
                        frame.setBackgroundColor(android.graphics.Color.BLACK)
                        frame.addView(view, FrameLayout.LayoutParams(-1, -1))
                        activity.addContentView(frame, ViewGroup.LayoutParams(-1, -1))
                        overlay = frame
                        fullscreenView = view
                        fullscreenCallback = callback
                        fullscreenStatus = "Fullscreen: requested"
                        Log.d("JA_PLAYBACK", "HTML5 fullscreen requested")
                    }
                    override fun onHideCustomView() {
                        val frame = overlay ?: return
                        (frame.parent as? ViewGroup)?.removeView(frame)
                        frame.removeAllViews()
                        overlay = null
                        fullscreenView = null
                        fullscreenCallback?.onCustomViewHidden()
                        fullscreenCallback = null
                        fullscreenStatus = "Fullscreen: closed"
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val nav = request ?: return true
                        val u = nav.url
                        val scheme = u.scheme?.lowercase().orEmpty()
                        val host = u.host?.lowercase().orEmpty()
                        Log.d("JA_PLAYBACK", "Navigation scheme=$scheme host=$host main=${nav.isForMainFrame} gesture=${nav.hasGesture()}")
                        if (scheme == "about" && u.toString() == "about:blank") return false
                        if (scheme != "https" && scheme != "http") {
                            Log.w("JA_PLAYBACK", "Blocked unsupported scheme=$scheme")
                            return true
                        }
                        // Compatibility mode allows ordinary HTTP(S) navigation without JA prompts.
                        // This isolates whether JA navigation restrictions break player initialization.
                        if (compatibilityMode) {
                            if (nav.isForMainFrame) navigationNote = "Compatibility navigation: $host (gesture=${nav.hasGesture()}, redirect=${nav.isRedirect})"
                            return false
                        }
                        // Protected mode: only main-frame cross-host navigations require confirmation.
                        // Subframes and resources are not intercepted; they may be essential.
                        if (nav.isForMainFrame &&
                            !host.equals(source.allowedHost, ignoreCase = true) &&
                            !approvedHosts.any { it.equals(host, ignoreCase = true) }) {
                            pendingNavigation = u.toString()
                            navigationNote = "Main-frame navigation requested: $host (gesture=${nav.hasGesture()}, redirect=${nav.isRedirect})"
                            Log.w("JA_PLAYBACK", navigationNote)
                            return true
                        }
                        return false
                    }
                    override fun onLoadResource(view: WebView?, url: String?) {
                        val uri = url?.let { Uri.parse(it) }
                        if (uri?.scheme == "https" && (url.contains("embed", true) || url.contains("iframe", true))) {
                            Log.d("JA_PLAYBACK", "Resource requested host=${uri.host} path=${uri.path?.take(90)}")
                        }
                    }
                    // Diagnostic mode: no request interception. Blocking ad/analytics
                    // endpoints can unintentionally prevent embedded player startup.
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        val host = url?.let { Uri.parse(it).host }
                        currentMainHost = host
                        Log.d("JA_PLAYBACK", "Main-frame load started host=$host")
                        if (host != null && !host.equals(source.allowedHost, ignoreCase = true)) {
                            navigationNote = "Main frame loaded $host (navigation may have bypassed interception)"
                            Log.w("JA_PLAYBACK", navigationNote)
                        }
                        webStatus = "Loading embedded player…"
                    }
                    override fun onPageFinished(view: WebView?, url: String?) {
                        webStatus = "Page loaded — player may still be initializing"
                        loadCompleted = true
                        Log.d("JA_PLAYBACK", "Player page loaded, host=${android.net.Uri.parse(url).host}")
                        // This inspects only the top-level DOM; cross-origin iframes are inaccessible.
                        view?.evaluateJavascript("""(function(){var f=document.querySelectorAll('iframe');return 'iframe count='+f.length+', hosts='+Array.from(f).slice(0,6).map(function(x){try{return new URL(x.src).hostname||'(inline)'}catch(e){return '(unknown)'}}).join(',');})()""") { value ->
                            iframeStatus = "Frame inspection: $value"
                            Log.d("JA_PLAYBACK", "Frame inspection: $value")
                        }
                        view?.evaluateJavascript("""(function(){var v=document.querySelector('video');if(!v)return 'No top-level video element (may be in iframe)';return 'Video: readyState='+v.readyState+', paused='+v.paused+', time='+Math.round(v.currentTime)+', duration='+Math.round(v.duration||0)+', size='+v.videoWidth+'x'+v.videoHeight+', error='+(v.error?v.error.code:'none');})()""") { value ->
                            videoStatus = "DOM inspection: $value"
                            Log.d("JA_PLAYBACK", "Video DOM inspection: $value")
                        }
                    }
                    override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, response: WebResourceResponse?) {
                        Log.w("JA_PLAYBACK", "HTTP ${response?.statusCode} main=${request?.isForMainFrame} host=${request?.url?.host}")
                        if (request?.isForMainFrame == true) playerError = "Player HTTP ${response?.statusCode}"
                    }
                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        if (request?.isForMainFrame == true) {
                            playerError = "Player page failed: ${error?.description ?: "Unknown WebView error"}"
                            Log.e("JA_PLAYBACK", "WebView error: ${error?.description}")
                        } else {
                            Log.w("JA_PLAYBACK", "Resource error: ${error?.description} host=${request?.url?.host}")
                        }
                    }
                }
                loadUrl(source.url)
            } }
            LaunchedEffect(web, navigationApproval) {
                val target = navigationApproval
                if (target != null) {
                    web.loadUrl(target)
                    navigationApproval = null
                }
            }
            LaunchedEffect(web, originalReloadCount) {
                if (originalReloadCount > 0) web.loadUrl(source.url)
            }
            LaunchedEffect(source.url, resetCount, loadCompleted) {
                if (loadCompleted) {
                    delay(15000)
                    if (webStatus == "Page loaded — player may still be initializing") {
                        webStatus = "Page loaded, but movie playback is not confirmed. Try Reset player or Open in browser."
                    }
                }
            }
            DisposableEffect(web) { onDispose {
                web.stopLoading()
                web.webChromeClient = null
                web.destroy()
            } }
            AndroidView(factory = { web }, modifier = Modifier.fillMaxWidth().height(350.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = showDiagnostics, onCheckedChange = { showDiagnostics = it })
                Text("Show diagnostics")
            }
            if (showDiagnostics) {
                // Compact, independently scrollable panel: long console errors remain readable
                // on narrow phones without pushing the rest of the player off-screen.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 190.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    @Composable
                    fun DiagnosticLine(value: String, tint: Color = Color.LightGray) {
                        Text(
                            text = value,
                            color = tint,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            softWrap = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    DiagnosticLine("Player: Embedded WebView; mode=${if (compatibilityMode) "compatibility" else "protected"}; progress=$pageProgress%")
                    DiagnosticLine("Host: ${currentMainHost ?: "unknown"}")
                    DiagnosticLine("Navigation: $navigationNote")
                    DiagnosticLine(videoStatus)
                    DiagnosticLine(iframeStatus)
                    DiagnosticLine(fullscreenStatus)
                    lastConsoleError?.let { DiagnosticLine("Console: $it", Color(0xFFFFA8A8)) }
                }
            }
            } // end optional embedded WebView
        }
    }
}
