package com.example.ja

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Standard Android browse/now-playing UI. Car screens are rendered separately by Android Auto. */
@Composable
internal fun JaMusicLabScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by JaMusicLabService.state.collectAsState()
    var section by remember { mutableStateOf("Home") }
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    var tracks by remember { mutableStateOf(emptyList<JaMusicCatalog.Track>()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
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
    fun play(list: List<JaMusicCatalog.Track>, index: Int) { notifications(); JaMusicLabService.playTracks(context, list, index) }
    LaunchedEffect(Unit) {
        if (!state.running && withContext(Dispatchers.IO) { JaMusicLabVault(context).read() } != null) JaMusicLabService.restore(context)
    }
    LaunchedEffect(section, submitted) {
        if (section == "Home" || section == "Search") {
            loading = true; message = ""
            try { tracks = withContext(Dispatchers.IO) { JaMusicCatalog.tracks(if (section == "Search") submitted else "") } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { tracks = emptyList(); message = (e as? JaMusicLabFailure)?.reason ?: "Could not load music." }
            finally { loading = false }
        }
    }
    if (signIn) JaMusicSignIn(onClose = { signIn = false }, onSession = {
        signIn = false; notifications(); JaMusicLabService.connect(context, it)
    })
    if (manual) AlertDialog(properties = androidx.compose.ui.window.DialogProperties(securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn),
        onDismissRequest = { manual = false; arl = "" }, title = { Text("Deezer session") },
        text = { OutlinedTextField(arl, { arl = it }, label = { Text("ARL") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation()) },
        confirmButton = { TextButton(onClick = { notifications(); JaMusicLabService.connect(context, arl); arl = ""; manual = false }) { Text("Connect") } },
        dismissButton = { TextButton(onClick = { arl = ""; manual = false }) { Text("Cancel") } })
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Home", "Search", "Queue", "Now Playing", "Account").forEach { name ->
                TvFocusButton(onClick = { section = name }, colors = ButtonDefaults.buttonColors(
                    containerColor = if (section == name) Color(0xFFE5A43B) else Color(0xFF354653),
                    contentColor = if (section == name) Color.Black else Color.White)) { Text(name) }
            }
        }
        if (section == "Search") Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            JaEntryField(query, { query = it }, Modifier.weight(1f), label = { Text("Find songs or artists") })
            TvFocusButton(onClick = { submitted = query }) { Text("Search") }
        }
        when (section) {
            "Account" -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Music • Deezer", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Text(state.account.ifBlank { "Sign in to your Deezer account" }, color = Color.White)
                Text("Track access depends on your Deezer plan and region. Sign-in uses Deezer’s own secure page.", color = Color.LightGray)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvFocusButton(onClick = { signIn = true }) { Text("Sign in") }
                    TvFocusButton(onClick = { JaMusicLabService.restore(context) }) { Text("Check session") }
                    TvFocusButton(onClick = { manual = true }) { Text("Use ARL") }
                    TvFocusButton(onClick = { JaMusicLabService.logout(context) }) { Text("Sign out") }
                }
                JaEntryField(trackLink, { trackLink = it }, Modifier.fillMaxWidth(), label = { Text("Deezer track link or ID") })
                TvFocusButton(onClick = { notifications(); JaMusicLabService.playTrack(context, trackLink) }, enabled = trackLink.isNotBlank()) { Text("Play track") }
                Text("Temporary cache: 64 MB, automatically evicted. Sign out clears it. Version 2.5.1b test.", color = Color.LightGray)
            }
            "Now Playing" -> Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AsyncImage(state.artwork, contentDescription = "Album artwork", modifier = Modifier.size(180.dp))
                Text(state.title.ifBlank { "Choose a track" }, style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Text(state.artist, color = Color.LightGray)
            }
            else -> {
                Text(if (section == "Queue") "Up Next" else if (section == "Search") "Search Results" else "Top Tracks",
                    style = MaterialTheme.typography.titleLarge, color = Color.White)
                val rows = if (section == "Queue") state.queue else tracks
                if (loading && section != "Queue") LinearProgressIndicator(Modifier.fillMaxWidth())
                if (message.isNotBlank() && section != "Queue") Text(message, color = Color(0xFFFFD078))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(rows, key = { index, track -> "${track.id}:$index" }) { index, track ->
                        TvFocusButton(onClick = { play(rows, index) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(
                            containerColor = if (state.queue.getOrNull(state.queueIndex)?.id == track.id) Color(0xFF725820) else Color(0xFF233747), contentColor = Color.White)) {
                            AsyncImage(track.art, contentDescription = null, modifier = Modifier.size(48.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            }
                            Text(musicTime(track.seconds * 1000))
                        }
                    }
                }
            }
        }
        MusicPlaybackControls(state, onOpen = { section = "Now Playing" })
    }
}

private fun musicTime(ms: Long) = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)

@Composable
private fun MusicPlaybackControls(state: JaMusicLabService.Companion.State, onOpen: () -> Unit) {
    val context = LocalContext.current
    var drag by remember { mutableStateOf<Float?>(null) }
    Surface(color = Color(0xFF182633), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(state.message, maxLines = 2, style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFD078))
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.title.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AsyncImage(state.artwork, contentDescription = null, modifier = Modifier.size(48.dp))
                    TvFocusButton(onClick = onOpen, modifier = Modifier.weight(1f)) {
                        Text("${state.title} • ${state.artist}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Slider(value = drag ?: state.position.toFloat().coerceIn(0f, state.duration.toFloat().coerceAtLeast(1f)),
                    onValueChange = { drag = it }, valueRange = 0f..state.duration.toFloat().coerceAtLeast(1f),
                    onValueChangeFinished = { drag?.let { JaMusicLabService.seekTo(it.toLong()) }; drag = null }, enabled = state.duration > 0)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                    Text("${musicTime(state.position)} / ${musicTime(state.duration)}", color = Color.White)
                    MusicTransportButton(R.drawable.ja_music_previous, "Previous", { JaMusicLabService.previous() })
                    MusicTransportButton(if (state.playing) R.drawable.ja_music_pause else R.drawable.ja_music_play,
                        if (state.playing) "Pause" else "Play", { JaMusicLabService.playPause() }, primary = true)
                    MusicTransportButton(R.drawable.ja_music_next, "Next", { JaMusicLabService.next() }, enabled = state.queueIndex + 1 < state.queue.size)
                    MusicTransportButton(R.drawable.ja_music_stop, "Stop", { JaMusicLabService.stop(context) })
                }
            }
        }
    }
}

@Composable
private fun MusicTransportButton(icon: Int, label: String, onClick: () -> Unit, enabled: Boolean = true, primary: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    FilledIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp).onFocusChanged { focused = it.isFocused }
        .then(if (focused) Modifier.border(2.dp, Color.White, CircleShape) else Modifier),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (primary) Color(0xFFE5A43B) else Color(0xFF354653),
            contentColor = if (primary) Color.Black else Color.White)) {
        Icon(painterResource(icon), contentDescription = label)
    }
}
