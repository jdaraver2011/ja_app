package com.example.ja

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.room.Room
import coil.compose.AsyncImage
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
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
        val r = PlaybackRequest(item, s, e)
        request = r; playback = provider.resolve(r)
        if (playback == null) error = "No authorized playback source configured for this title."
        else scope.launch { val old = dao.find(item.id, item.type, s, e); dao.save(WatchItem(item.id, item.type, s, e, item.title, item.poster, old?.favorite ?: false, old?.positionMs ?: 0)); refreshHistory() }
    }
    BackHandler(playback != null || selected != null) { if (playback != null) playback = null else selected = null }
    Column(Modifier.fillMaxSize().background(bg).padding(16.dp)) {
        Text("PLEXSTREAM", style = MaterialTheme.typography.headlineMedium, color = accent)
        Spacer(Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("Home", "Movies", "TV", "Search", "Favorites", "Continue")) { t ->
                Button(onClick = { tab = t; selected = null; playback = null }, colors = ButtonDefaults.buttonColors(containerColor = if (tab == t) accent else Color.DarkGray)) { Text(t, color = Color.Black) }
            }
        }
        Spacer(Modifier.height(12.dp))
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
        error?.let { Text(it, color = Color(0xFFFFA8A8)) }
    }
}

@Composable fun PlayerScreen(source: PlaybackSource, request: PlaybackRequest?, dao: WatchDao, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Button(onClick = onClose) { Text("Back to details") }
    when (source) {
        is PlaybackSource.Native -> {
            val player = remember(source.url) { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(source.url)); prepare(); playWhenReady = true } }
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
            // Embed position cannot be reliably measured; never claim exact resume support.
            val web = remember(source.url) { WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val u = request?.url ?: return true
                        return u.scheme != "https" || !u.host.equals(source.allowedHost, true)
                    }
                }
                loadUrl(source.url)
            } }
            DisposableEffect(web) { onDispose { web.stopLoading(); web.destroy() } }
            AndroidView(factory = { web }, modifier = Modifier.fillMaxWidth().height(350.dp))
        }
    }
}
