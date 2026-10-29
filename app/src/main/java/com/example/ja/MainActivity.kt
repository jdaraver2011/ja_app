package com.example.ja

import android.os.Bundle
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.app.Activity
import android.app.Dialog
import android.widget.Button
import android.widget.LinearLayout
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
import androidx.compose.ui.text.style.TextAlign
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
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = accent, background = bg, surface = Color(0xFF202832), onBackground = Color.White, onSurface = Color.White, onSurfaceVariant = Color.LightGray)) { StreamingApp(db.dao()) } }
    }
}

@Composable fun StreamingApp(dao: WatchDao) {
    val appContext = LocalContext.current.applicationContext
    val repo = remember { TmdbRepository() }
    val provider = remember { PlaybackRegistry() }
    val scope = rememberCoroutineScope()
    val settings = remember { PlayerControlSettings(context = appContext) }
    var showBack by remember { mutableStateOf(settings.showBack) }
    var showRestore by remember { mutableStateOf(settings.showRestore) }
    var showInfo by remember { mutableStateOf(settings.showInfo) }
    var autoStart by remember { mutableStateOf(settings.autoStart) }
    var interactionDiagnostics by remember { mutableStateOf(settings.interactionDiagnostics) }
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
    // Playback occupies the entire activity instead of the catalog's padded column.
    if (playback != null) {
        PlayerScreen(playback!!, request, dao, showBack, showRestore, showInfo, autoStart, interactionDiagnostics, onClose = { playback = null })
        return
    }
    Column(Modifier.fillMaxSize().background(bg).padding(16.dp)) {
        Text("JA", style = MaterialTheme.typography.headlineMedium, color = accent)
        Spacer(Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("Home", "Movies", "TV", "Search", "Favorites", "Continue", "Settings")) { t ->
                Button(onClick = { tab = t; selected = null; playback = null }, colors = ButtonDefaults.buttonColors(containerColor = if (tab == t) accent else Color.DarkGray)) { Text(t, color = Color.Black) }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (playbackLoading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Looking up playback source…") }
        error?.let { Text(it, color = Color(0xFFFFA8A8)) }
        if (tab == "Settings" && selected == null) {
            Text("Player controls", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Text("Choose which controls appear over GeckoView. Android's system Back action still exits playback.", color = Color.LightGray)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = showBack, onCheckedChange = { showBack = it; settings.showBack = it })
                Text("Show Back button", color = Color.White)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = showRestore, onCheckedChange = { showRestore = it; settings.showRestore = it })
                Text("Show Restore Episode button", color = Color.White)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = showInfo, onCheckedChange = { showInfo = it; settings.showInfo = it })
                Text("Show Info / diagnostics button", color = Color.White)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = autoStart, onCheckedChange = { autoStart = it; settings.autoStart = it })
                Text("Experimental Auto-Start (may require manual Play)", color = Color.White)
            }
            Text("Auto-Start tests the website’s Play control. Keep disabled if playback behaves unexpectedly.", color = Color.LightGray)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = interactionDiagnostics, onCheckedChange = { interactionDiagnostics = it; settings.interactionDiagnostics = it })
                Text("Player Interaction Diagnostics (experimental)", color = Color.White)
            }
            Text("Shows a small in-player diagnostic panel and logs player interactions. Restart playback after changing this setting.", color = Color.LightGray)
            Text("Settings are saved on this device.", color = Color.LightGray)
        } else if (selected != null) {
            val item = selected!!
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AsyncImage(item.posterUrl, null, Modifier.width(125.dp).height(185.dp), contentScale = ContentScale.Crop)
                Column {
                    Text(item.title, style = MaterialTheme.typography.headlineSmall, color = Color.White)
                    Text("TMDB rating: ${"%.1f".format(item.rating)}", color = Color.White)
                    Text(item.overview, maxLines = 5, overflow = TextOverflow.Ellipsis, color = Color.White)
                    Row {
                        Button(onClick = { if (item.type == "movie") play(item) else play(item, season, episodes.firstOrNull()?.episode ?: 1) }) { Text("Play") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { scope.launch { val old = dao.find(item.id, item.type, 0, 0); dao.save(WatchItem(item.id, item.type, title = item.title, poster = item.poster, favorite = !(old?.favorite ?: false), positionMs = old?.positionMs ?: 0)); refreshHistory() } }) { Text("Toggle favorite") }
                    }
                }
            }
            if (item.type == "tv") {
                Text("Seasons", style = MaterialTheme.typography.titleLarge, color = Color.White)
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
            Text(if (tab == "Home") "Trending this week" else tab, style = MaterialTheme.typography.titleLarge, color = Color.White)
            Spacer(Modifier.height(8.dp))
            Column(Modifier.verticalScroll(rememberScrollState())) {
                results.chunked(5).forEach { group ->
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 14.dp)) {
                        items(group) { item ->
                            Column(Modifier.width(140.dp).clickable { open(item) }) {
                                AsyncImage(item.posterUrl, item.title, Modifier.fillMaxWidth().height(190.dp).border(1.dp, Color.DarkGray, RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                                Text(item.title, modifier = Modifier.fillMaxWidth(), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable fun PlayerScreen(source: PlaybackSource, request: PlaybackRequest?, dao: WatchDao, showBack: Boolean, showRestore: Boolean, showInfo: Boolean, autoStart: Boolean, interactionDiagnostics: Boolean, onClose: () -> Unit) {
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
            Box(Modifier.fillMaxSize()) {
                AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = true } }, modifier = Modifier.fillMaxSize())
                Button(onClick = onClose, modifier = Modifier.align(Alignment.TopStart)) { Text("Back") }
            }
        }
        is PlaybackSource.Embed -> {
            // GeckoView is the sole embedded player for website-based sources.
            // No Chrome Custom Tabs, external browser, or Android WebView controls.
            Box(Modifier.fillMaxSize()) {
                GeckoExperimentalPlayer(url = source.url, showRestore = showRestore, showInfo = showInfo, autoStart = autoStart, interactionDiagnostics = interactionDiagnostics)
                if (showBack) {
                    Button(onClick = onClose, modifier = Modifier.align(Alignment.TopStart)) { Text("Back") }
                }
            }
        }
    }
}


/** Persist player overlay preferences between JA launches. */
private class PlayerControlSettings(context: Context) {
    private val prefs = context.getSharedPreferences("ja_player_controls", Context.MODE_PRIVATE)
    var showBack: Boolean
        get() = prefs.getBoolean("back", false)
        set(value) { prefs.edit().putBoolean("back", value).apply() }
    var showRestore: Boolean
        get() = prefs.getBoolean("restore", false)
        set(value) { prefs.edit().putBoolean("restore", value).apply() }
    var autoStart: Boolean
        get() = prefs.getBoolean("auto_start", false)
        set(value) { prefs.edit().putBoolean("auto_start", value).apply() }
    var interactionDiagnostics: Boolean
        get() = prefs.getBoolean("interaction_diagnostics", false)
        set(value) { prefs.edit().putBoolean("interaction_diagnostics", value).apply() }
    var showInfo: Boolean
        get() = prefs.getBoolean("info", false)
        set(value) { prefs.edit().putBoolean("info", value).apply() }
}
