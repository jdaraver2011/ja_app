package com.example.ja

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.*

private val musicGold = Color(0xFFE5A43B)
private val musicPanel = Color(0xFF233747)
private data class MusicRoute(val title: String, val kind: String = "root", val id: String = "", val path: String = "")
private data class MusicShelf(val title: String, val page: JaMusicCatalog.Page, val path: String, val kind: String)

/** Full-screen phone/TV browse UI; Android Auto retains its host-rendered audio templates. */
@Composable
internal fun JaMusicLabScreen(modifier: Modifier = Modifier, onExit: () -> Unit = {}) {
    val context = LocalContext.current
    val state by JaMusicLabService.state.collectAsState()
    val library = remember { JaMusicLibrary(context) }
    val revision by library.changes.collectAsState()
    val scope = rememberCoroutineScope()
    val stack = remember { mutableStateListOf(MusicRoute("Home")) }
    val route = stack.last()
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    var searchKind by remember { mutableStateOf("track") }
    var libraryKind by remember { mutableStateOf("Tracks") }
    var artistKind by remember(route) { mutableStateOf("track") }
    var page by remember(route, submitted, searchKind, artistKind) { mutableStateOf(JaMusicCatalog.Page()) }
    var shelves by remember { mutableStateOf(emptyList<MusicShelf>()) }
    var detail by remember(route) { mutableStateOf<JaMusicCatalog.Card?>(null) }
    var loading by remember { mutableStateOf(false) }
    var message by remember(route) { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }
    var selectedTrack by remember { mutableStateOf<JaMusicCatalog.Track?>(null) }
    var addTrack by remember { mutableStateOf<JaMusicCatalog.Track?>(null) }
    var nameDialog by remember { mutableStateOf<String?>(null) }
    var playlistName by remember { mutableStateOf("") }
    var deleteDialog by remember { mutableStateOf(false) }
    var signIn by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }
    var arl by remember { mutableStateOf("") }
    var trackLink by remember { mutableStateOf("") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun notifications() {
        if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    fun open(r: MusicRoute) { stack.add(r) }
    fun root(name: String) { stack.clear(); stack.add(MusicRoute(name)) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) else onExit() }
    fun card(c: JaMusicCatalog.Card) { open(MusicRoute(c.title, c.kind, c.id)) }
    fun play(tracks: List<JaMusicCatalog.Track>, index: Int = 0) { notifications(); JaMusicLabService.playTracks(context, tracks, index) }
    val localPlaylist = remember(route, revision) { library.playlists().firstOrNull { it.id == route.id } }
    val tracks = when {
        route.kind == "local" -> localPlaylist?.tracks.orEmpty()
        route.title == "Liked Songs" -> library.liked()
        route.title == "Queue" -> state.queue
        else -> page.tracks
    }
    BackHandler { back() }
    LaunchedEffect(Unit) {
        if (!state.running && withContext(Dispatchers.IO) { JaMusicLabVault(context).read() } != null) JaMusicLabService.restore(context)
    }
    LaunchedEffect(route, submitted, searchKind, artistKind, reload) {
        loading = true; message = ""; page = JaMusicCatalog.Page()
        try {
            when {
                route.title == "Home" && route.kind == "root" -> {
                    val specs = listOf(Triple("Top Tracks", "chart/0/tracks?limit=20", "track"),
                        Triple("Popular Albums", "chart/0/albums?limit=20", "album"), Triple("Popular Artists", "chart/0/artists?limit=20", "artist"),
                        Triple("Featured Playlists", "chart/0/playlists?limit=20", "playlist"), Triple("New Releases", "editorial/0/releases?limit=20", "album"))
                    shelves = coroutineScope { specs.map { (title, path, kind) -> async(Dispatchers.IO) {
                        runCatching { MusicShelf(title, JaMusicCatalog.page(path, kind), path.replace("limit=20", "limit=50"), kind) }.getOrNull()
                    } }.awaitAll().filterNotNull().filter { it.page.tracks.isNotEmpty() || it.page.cards.isNotEmpty() } }
                    if (shelves.isEmpty()) message = "Deezer catalogue unavailable. Try again."
                }
                route.title == "Explore" -> page = withContext(Dispatchers.IO) { JaMusicCatalog.page("genre", "genre") }
                route.title == "Search" -> if (submitted.isNotBlank()) {
                    page = withContext(Dispatchers.IO) { JaMusicCatalog.search(submitted, searchKind) }
                    library.rememberSearch(submitted, searchKind, page.cards.firstOrNull(), page.tracks.firstOrNull())
                }
                route.kind == "genre" -> {
                    page = withContext(Dispatchers.IO) { JaMusicCatalog.page("chart/${route.id}/tracks?limit=50") }
                }
                route.kind in listOf("artist", "album", "playlist") -> {
                    val d = withContext(Dispatchers.IO) { JaMusicCatalog.detail(route.id, route.kind) }
                    detail = JaMusicCatalog.Card(route.id, d.optString(if (route.kind == "artist") "name" else "title"),
                        d.optJSONObject("artist")?.optString("name") ?: d.optString("release_date"),
                        d.optString(if (route.kind == "album") "cover_medium" else "picture_medium"), route.kind)
                    val path = if (route.kind == "artist") when (artistKind) {
                        "album" -> "artist/${route.id}/albums?limit=50"
                        "artist" -> "artist/${route.id}/related?limit=50"
                        else -> "artist/${route.id}/top?limit=50"
                    } else "${route.kind}/${route.id}/tracks?limit=50"
                    page = withContext(Dispatchers.IO) { JaMusicCatalog.page(path, if (route.kind == "artist") artistKind else "track", if (route.kind == "album") d else null) }
                }
                route.kind == "page" -> page = withContext(Dispatchers.IO) { JaMusicCatalog.page(route.path, route.id) }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { message = "Could not load this catalogue. Check your connection and retry." }
        finally { loading = false }
    }
    if (signIn) JaMusicSignIn(onClose = { signIn = false }, onSession = { signIn = false; notifications(); JaMusicLabService.connect(context, it) })
    if (manual) AlertDialog(properties = androidx.compose.ui.window.DialogProperties(securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn),
        onDismissRequest = { manual = false; arl = "" }, title = { Text("Deezer session") },
        text = { OutlinedTextField(arl, { arl = it }, label = { Text("ARL") }, singleLine = true, visualTransformation = PasswordVisualTransformation()) },
        confirmButton = { TextButton(onClick = { notifications(); JaMusicLabService.connect(context, arl); arl = ""; manual = false }) { Text("Connect") } },
        dismissButton = { TextButton(onClick = { manual = false; arl = "" }) { Text("Cancel") } })
    if (nameDialog != null) AlertDialog(onDismissRequest = { nameDialog = null }, title = { Text(if (nameDialog == "new") "Create JA Playlist" else "Rename Playlist") },
        text = { JaEntryField(playlistName, { playlistName = it }, label = { Text("Playlist name") }) },
        confirmButton = { TextButton(enabled = playlistName.isNotBlank(), onClick = {
            if (nameDialog == "new") { val id = library.create(playlistName); addTrack?.let { library.add(id, it) }; addTrack = null }
            else library.rename(route.id, playlistName)
            nameDialog = null
        }) { Text("Save") } }, dismissButton = { TextButton(onClick = { nameDialog = null }) { Text("Cancel") } })
    if (deleteDialog) AlertDialog(onDismissRequest = { deleteDialog = false }, title = { Text("Delete this JA playlist?") },
        text = { Text("Songs remain available in the Deezer catalogue.") }, confirmButton = { TextButton(onClick = { library.delete(route.id); deleteDialog = false; back() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("Cancel") } })
    selectedTrack?.let { t -> AlertDialog(onDismissRequest = { selectedTrack = null }, title = { Text(t.title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MusicButton(if (library.liked().any { it.id == t.id }) "Remove from Liked Songs" else "Like Song", { library.toggleLike(t); selectedTrack = null })
            MusicButton("Add to JA Playlist", { addTrack = t; selectedTrack = null })
            if (t.artistId.isNotBlank()) MusicButton("View Artist", { selectedTrack = null; open(MusicRoute(t.artist, "artist", t.artistId)) })
            if (t.albumId.isNotBlank()) MusicButton("View Album", { selectedTrack = null; open(MusicRoute(t.album, "album", t.albumId)) })
            if (route.kind == "local") {
                MusicButton("Move Earlier", { library.move(route.id, t.id, -1); selectedTrack = null })
                MusicButton("Move Later", { library.move(route.id, t.id, 1); selectedTrack = null })
                MusicButton("Remove from Playlist", { library.remove(route.id, t.id); selectedTrack = null })
            }
        }
    }, confirmButton = { TextButton(onClick = { selectedTrack = null }) { Text("Close") } }) }
    addTrack?.let { t -> if (nameDialog == null) AlertDialog(onDismissRequest = { addTrack = null }, title = { Text("Add to JA Playlist") }, text = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { MusicButton("Create Playlist", { playlistName = ""; nameDialog = "new" }) }
            items(library.playlists(), key = { it.id }) { p -> MusicButton(p.name, { library.add(p.id, t); addTrack = null }) }
        }
    }, confirmButton = { TextButton(onClick = { addTrack = null }) { Text("Cancel") } }) }

    if (route.title == "Visualizer") {
        JaMusicVisualizer(onBack = { back() }); return
    }
    if (route.title == "Lyrics") {
        MusicLyrics(state, onBack = { back() }); return
    }
    Column(modifier.fillMaxSize().background(Color(0xFF101923)).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MusicButton(if (stack.size > 1) "‹ Back" else "‹ JA", { back() })
            Text("JA Music", color = musicGold, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            MusicButton("Account", { open(MusicRoute("Account")) })
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Home", "Explore", "Search", "Library", "Now Playing").forEach { name -> MusicButton(name, { root(name) }, active = route.title == name) }
        }
        if (route.title == "Now Playing") {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val wide = maxWidth > 600.dp
                if (wide) Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    AsyncImage(state.artwork, "Album artwork", Modifier.weight(1f).fillMaxHeight().padding(16.dp))
                    Column(Modifier.weight(1.2f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        MusicNowPlaying(state, library, { open(MusicRoute(it)) }, { selectedTrack = it })
                    }
                } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AsyncImage(state.artwork, "Album artwork", Modifier.fillMaxWidth().height(220.dp))
                    MusicNowPlaying(state, library, { open(MusicRoute(it)) }, { selectedTrack = it })
                }
            }
        } else if (route.title == "Account") {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MusicHeading("Deezer Account")
                Text(state.account.ifBlank { "Sign in to your Deezer account" }, color = Color.White)
                Text("Playback and lyrics availability depend on your account and region. JA playlists are stored on this device.", color = Color.LightGray)
                MusicButton("Sign in with Deezer", { signIn = true })
                MusicButton("Check Session", { JaMusicLabService.restore(context) })
                MusicButton("Use ARL", { manual = true })
                MusicButton("Sign Out", { JaMusicLabService.logout(context) })
                JaEntryField(trackLink, { trackLink = it }, Modifier.fillMaxWidth(), label = { Text("Deezer track URL or ID") })
                MusicButton("Play Track", { notifications(); JaMusicLabService.playTrack(context, trackLink) }, enabled = trackLink.isNotBlank())
                Text("Audio cache: 64 MB, automatically evicted. Visualizer: Butterchurn, a MilkDrop-compatible renderer (MIT).", color = Color.LightGray)
            }
        } else {
            if (route.title == "Search") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    JaEntryField(query, { query = it }, Modifier.weight(1f), label = { Text("Songs, Artists, Albums and Playlists") })
                    MusicButton("Search", { submitted = query.trim() })
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("track" to "Songs", "artist" to "Artists", "album" to "Albums", "playlist" to "Playlists").forEach { (kind, title) -> MusicButton(title, { searchKind = kind }, active = kind == searchKind) }
                }
            }
            if (route.title == "Library") Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Tracks", "Artists", "Albums", "Playlists").forEach { name -> MusicButton(name, { libraryKind = name }, active = libraryKind == name) }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (message.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(message, color = musicGold, modifier = Modifier.weight(1f)); MusicButton("Retry", { reload++ })
            }
            key(route, searchKind, submitted, artistKind, libraryKind) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (route.title == "Home" && route.kind == "root") {
                        items(shelves, key = { it.title }) { shelf ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                MusicHeading(shelf.title, Modifier.weight(1f))
                                MusicButton("See All", { open(MusicRoute(shelf.title, "page", shelf.kind, shelf.path)) })
                            }
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (shelf.page.tracks.isNotEmpty()) itemsIndexed(shelf.page.tracks, key = { _, t -> t.id }) { i, t -> MusicCard(t.title, t.artist, t.art, { play(shelf.page.tracks, i) }) }
                                else items(shelf.page.cards, key = { it.id }) { c -> MusicCard(c.title, c.subtitle, c.art, { card(c) }, round = c.kind == "artist") }
                            }
                        }
                    } else if (route.title == "Library") {
                        item { Row(verticalAlignment = Alignment.CenterVertically) {
                            MusicHeading("Your JA Library", Modifier.weight(1f))
                            if (libraryKind == "Playlists") MusicButton("Create Playlist", { playlistName = ""; nameDialog = "new" })
                        } }
                        if (libraryKind == "Tracks") {
                            item { MusicButton("Liked Songs · ${library.liked().size}", { open(MusicRoute("Liked Songs")) }, Modifier.fillMaxWidth()) }
                            itemsIndexed(library.liked(), key = { _, t -> t.id }) { i, t ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    MusicButton("${t.title} · ${t.artist}", { play(library.liked(), i) }, Modifier.weight(1f))
                                    MusicButton("•••", { selectedTrack = t })
                                }
                            }
                        } else {
                            if (libraryKind == "Playlists") items(library.playlists(), key = { "local:${it.id}" }) { p -> MusicButton("${p.name} · ${p.tracks.size} songs", { open(MusicRoute(p.name, "local", p.id)) }, Modifier.fillMaxWidth()) }
                            val kind = when (libraryKind) { "Artists" -> "artist"; "Albums" -> "album"; else -> "playlist" }
                            val saved = library.saved().filter { it.kind == kind }
                            items(saved.chunked(3), key = { it.first().id }) { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { c -> MusicCard(c.title, c.subtitle, c.art, { card(c) }, Modifier.weight(1f), round = c.kind == "artist") }
                                repeat(3-row.size) { Spacer(Modifier.weight(1f)) }
                            } }
                            if (saved.isEmpty() && (kind != "playlist" || library.playlists().isEmpty())) item { Text("No saved ${libraryKind.lowercase()} yet.", color = Color.LightGray) }
                        }
                        item { Text("Your JA library is saved on this device. Use song menus to like tracks and Save on artist, album and playlist pages.", color = Color.LightGray) }
                    } else {
                        if (route.title == "Search" && submitted.isBlank()) {
                            val recent = library.recentSearches()
                            item { MusicHeading("Recent Searches") }
                            if (recent.isEmpty()) item { Text("Your recent searches will appear here.", color = Color.LightGray) }
                            items(recent, key = { "recent:${it.kind}:${it.query}" }) { entry ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    MusicButton("", {
                                        if (entry.kind == "artist" && entry.id.isNotBlank()) open(MusicRoute(entry.query, "artist", entry.id))
                                        else { query = entry.query; searchKind = entry.kind; submitted = entry.query }
                                    }, Modifier.weight(1f)) {
                                        AsyncImage(entry.art, null, Modifier.size(56.dp).clip(CircleShape))
                                        Column(Modifier.weight(1f)) {
                                            Text(entry.query, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            Text(entry.subtitle, color = Color.LightGray, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                    MusicButton("×", { library.removeRecentSearch(entry.query, entry.kind) })
                                }
                            }
                            if (recent.isNotEmpty()) item { MusicButton("Clear Recent Searches", { library.clearRecentSearches() }) }
                        } else item { MusicHeading(if (route.title == "Search") "${searchKind.replaceFirstChar { it.uppercase() }} Results" else localPlaylist?.name ?: route.title) }
                        detail?.let { c -> item {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                AsyncImage(c.art, null, Modifier.size(86.dp)); Column(Modifier.weight(1f)) {
                                    Text(c.title, color = Color.White, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(c.subtitle, color = Color.LightGray, minLines = 1, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                MusicButton(if (library.saved().any { it.id == c.id && it.kind == c.kind }) "Unsave" else "Save", { library.toggleSave(c) })
                            }
                        } }
                        if (route.kind == "artist") item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("track" to "Top Songs", "album" to "Albums", "artist" to "Similar Artists").forEach { (k, t) -> MusicButton(t, { artistKind = k }, active = artistKind == k) }
                        } }
                        if (route.kind == "genre") item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("albums" to "Albums", "artists" to "Artists", "playlists" to "Playlists").forEach { (k, t) -> MusicButton(t, { open(MusicRoute("${route.title} · $t", "page", k.dropLast(1), "chart/${route.id}/$k?limit=50")) }) }
                        } }
                        if (route.kind == "local") item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            MusicButton("Rename", { playlistName = localPlaylist?.name.orEmpty(); nameDialog = "rename" })
                            MusicButton("Delete", { deleteDialog = true })
                            MusicButton("Add Songs", { root("Search") })
                        } }
                        if (tracks.isNotEmpty()) item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MusicButton("Play All", { play(tracks) }); MusicButton("Shuffle", { play(tracks.shuffled()) })
                            Text("${tracks.size} songs", color = Color.LightGray, modifier = Modifier.align(Alignment.CenterVertically))
                        } }
                        itemsIndexed(tracks, key = { i, t -> "${t.id}:$i" }) { i, t ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                MusicButton("", { play(tracks, i) }, Modifier.weight(1f), active = state.queue.getOrNull(state.queueIndex)?.id == t.id) {
                                    AsyncImage(t.art, null, Modifier.size(40.dp)); Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(t.title, minLines = 1, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(t.artist, minLines = 1, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text(musicTime(t.seconds * 1000))
                                }
                                MusicButton("•••", { selectedTrack = t })
                            }
                        }
                        if (page.cards.isNotEmpty()) items(page.cards.chunked(3), key = { it.first().id }) { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { c -> MusicCard(c.title, c.subtitle, c.art, { card(c) }, Modifier.weight(1f), round = c.kind == "artist") }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                        if (!loading && tracks.isEmpty() && page.cards.isEmpty() && !(route.title == "Search" && submitted.isBlank())) item { Text("No music here yet.", color = Color.LightGray) }
                        if (page.next.isNotBlank()) item { MusicButton("Load More", {
                            scope.launch {
                                loading = true
                                try { val more = withContext(Dispatchers.IO) { JaMusicCatalog.page(page.next, if (route.kind == "artist") artistKind else if (route.kind == "page") route.id else if (route.title == "Search") searchKind else "track",
                                    detail?.takeIf { it.kind == "album" }?.let { org.json.JSONObject().put("id", it.id).put("title", it.title).put("cover_medium", it.art) }) }
                                    page = JaMusicCatalog.Page((page.tracks + more.tracks).distinctBy { it.id }, (page.cards + more.cards).distinctBy { it.id }, more.next)
                                } catch (e: CancellationException) { throw e } catch (_: Exception) { message = "Could not load more. Try again." } finally { loading = false }
                            }
                        }, enabled = !loading) }
                    }
                }
            }
        }
        if (route.title != "Now Playing") MusicMiniPlayer(state, { open(MusicRoute("Now Playing")) })
    }
}

@Composable private fun MusicHeading(text: String, modifier: Modifier = Modifier) { Text(text, modifier, color = Color.White, style = MaterialTheme.typography.titleLarge) }
@Composable private fun MusicButton(text: String, click: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false, enabled: Boolean = true, content: (@Composable RowScope.() -> Unit)? = null) {
    TvFocusButton(onClick = click, modifier = modifier, enabled = enabled, colors = ButtonDefaults.buttonColors(containerColor = if (active) musicGold else musicPanel, contentColor = if (active) Color.Black else Color.White)) {
        if (content != null) content() else Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
@Composable private fun MusicCard(title: String, subtitle: String, art: String, click: () -> Unit, modifier: Modifier = Modifier.width(160.dp), round: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier.clip(RoundedCornerShape(8.dp)).onFocusChanged { focused = it.isFocused }
        .then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
        .clickable(role = Role.Button, onClick = click).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(shape = if (round) CircleShape else MaterialTheme.shapes.small, color = Color.Transparent) { AsyncImage(art, null, Modifier.size(100.dp)) }
        Text(title, color = Color.White, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotBlank()) Text(subtitle, color = Color.LightGray, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
    }
}
private fun musicTime(ms: Long) = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)
@Composable private fun MusicMiniPlayer(state: JaMusicLabService.Companion.State, open: () -> Unit) {
    Surface(color = Color(0xFF182633), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(6.dp)) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.title.isBlank()) Text(state.message, color = musicGold, maxLines = 2, style = MaterialTheme.typography.bodySmall)
            else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AsyncImage(state.artwork, null, Modifier.size(44.dp))
                MusicButton("", open, Modifier.weight(1f)) {
                    Column(Modifier.weight(1f)) {
                        Text(state.title, color = Color.White, minLines = 1, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(state.artist, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                MusicTransportButton(if (state.playing) R.drawable.ja_music_pause else R.drawable.ja_music_play, if (state.playing) "Pause" else "Play", { JaMusicLabService.playPause() }, primary = true)
                MusicTransportButton(R.drawable.ja_music_next, "Next", { JaMusicLabService.next() }, enabled = state.queue.isNotEmpty())
            }
        }
    }
}
@Composable private fun MusicNowPlaying(state: JaMusicLabService.Companion.State, library: JaMusicLibrary, open: (String) -> Unit, options: (JaMusicCatalog.Track) -> Unit) {
    val context = LocalContext.current
    val current = state.queue.getOrNull(state.queueIndex)
    var drag by remember { mutableStateOf<Float?>(null) }
    Text(state.title.ifBlank { "Choose a song" }, color = Color.White, minLines = 1, maxLines = 2,
        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.headlineSmall)
    Text(state.artist, color = Color.LightGray, maxLines = 2, overflow = TextOverflow.Ellipsis)
    current?.album?.takeIf { it.isNotBlank() }?.let { Text(it, color = Color.LightGray, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    Text(state.message, color = musicGold, style = MaterialTheme.typography.bodySmall)
    Slider(value = drag ?: state.position.toFloat().coerceIn(0f, state.duration.toFloat().coerceAtLeast(1f)), onValueChange = { drag = it },
        valueRange = 0f..state.duration.toFloat().coerceAtLeast(1f), onValueChangeFinished = { drag?.let { JaMusicLabService.seekTo(it.toLong()) }; drag = null }, enabled = state.duration > 0)
    Text("${musicTime(state.position)} / ${musicTime(state.duration)}", color = Color.LightGray)
    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MusicTransportButton(R.drawable.ja_music_previous, "Previous", { JaMusicLabService.previous() })
        MusicTransportButton(if (state.playing) R.drawable.ja_music_pause else R.drawable.ja_music_play, if (state.playing) "Pause" else "Play", { JaMusicLabService.playPause() }, primary = true)
        MusicTransportButton(R.drawable.ja_music_next, "Next", { JaMusicLabService.next() })
        MusicTransportButton(R.drawable.ja_music_stop, "Stop", { JaMusicLabService.stop(context) })
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MusicButton("Shuffle ${if (state.shuffle) "On" else "Off"}", { JaMusicLabService.toggleShuffle() }, active = state.shuffle)
        MusicButton("Repeat ${listOf("Off", "One", "All").getOrElse(state.repeat) { "Off" }}", { JaMusicLabService.cycleRepeat() }, active = state.repeat != 0)
        MusicButton("Queue", { open("Queue") })
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MusicButton("Cast Screen", {
            try {
                context.startActivity(Intent(Settings.ACTION_CAST_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, "Open Quick Settings > Cast to choose a TV.", Toast.LENGTH_LONG).show()
            }
        })
        MusicButton("Lyrics", { open("Lyrics") }, enabled = current != null)
        MusicButton("Visualizer", { open("Visualizer") }, enabled = current != null)
        if (current != null) {
            MusicButton(if (library.liked().any { it.id == current.id }) "♥ Liked" else "♡ Like", { library.toggleLike(current) })
            MusicButton("•••", { options(current) })
        }
    }
}
@Composable private fun MusicTransportButton(icon: Int, label: String, onClick: () -> Unit, enabled: Boolean = true, primary: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    FilledIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp).onFocusChanged { focused = it.isFocused }
        .then(if (focused) Modifier.border(2.dp, Color.White, CircleShape) else Modifier),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (primary) musicGold else musicPanel, contentColor = if (primary) Color.Black else Color.White)) {
        Icon(painterResource(icon), contentDescription = label)
    }
}
@Composable private fun MusicLyrics(state: JaMusicLabService.Companion.State, onBack: () -> Unit) {
    val context = LocalContext.current
    val id = state.queue.getOrNull(state.queueIndex)?.id
    var lyrics by remember(id) { mutableStateOf<JaMusicLabAccount.Lyrics?>(null) }
    var message by remember(id) { mutableStateOf("Loading lyrics…") }
    val scroll = androidx.compose.foundation.lazy.rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(id) {
        if (id == null) { message = "Choose a song first."; return@LaunchedEffect }
        try {
            lyrics = withContext(Dispatchers.IO) {
                val arl = JaMusicLabVault(context).read() ?: throw JaMusicLabFailure("SESSION_REQUIRED")
                val client = JaMusicLabAccount(arl)
                try { client.lyrics(id, client.validate()) } finally { client.cancel() }
            }
            message = if (lyrics!!.text.isBlank() && lyrics!!.lines.isEmpty()) "Lyrics are not available for this song." else ""
        } catch (e: CancellationException) { throw e } catch (_: Exception) { message = "Lyrics are not available for this song or account." }
    }
    val active = lyrics?.lines?.indexOfLast { it.milliseconds <= state.position } ?: -1
    LaunchedEffect(active, follow) { if (follow && active >= 0) scroll.animateScrollToItem(active) }
    Column(Modifier.fillMaxSize().background(Color(0xFF101923)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { MusicButton("‹ Player", onBack); Spacer(Modifier.width(12.dp)); MusicHeading("Lyrics", Modifier.weight(1f)); MusicButton(if (follow) "Follow On" else "Follow Off", { follow = !follow }) }
        Text("${state.title} · ${state.artist}", color = musicGold)
        if (message.isNotBlank()) Text(message, color = Color.White)
        LazyColumn(Modifier.weight(1f), state = scroll, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            val data = lyrics
            if (data != null && data.lines.isNotEmpty()) itemsIndexed(data.lines) { i, line -> Text(line.text.ifBlank { "♪" }, color = if (i == active) musicGold else Color.White,
                style = MaterialTheme.typography.headlineSmall, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) }
            else if (data != null) item { Text(data.text, color = Color.White, style = MaterialTheme.typography.headlineSmall) }
        }
        lyrics?.credits?.takeIf { it.isNotBlank() }?.let { Text(it, color = Color.LightGray, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MusicButton(if (state.playing) "Pause" else "Play", { JaMusicLabService.playPause() }); MusicButton("Next", { JaMusicLabService.next() })
        }
    }
}
