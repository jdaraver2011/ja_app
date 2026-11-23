package com.example.ja

import androidx.compose.foundation.layout.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Kept above the details/player branch so returning preserves both scroll axes. */
internal class JaNetworksBrowseState {
    var directory by mutableStateOf<TmdbNetworkDirectory?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    val jump = JaRowJumpState("TV:Networks")
    val listState = LazyListState()
    val rowStates = mutableMapOf<Int, LazyListState>()
}

@Composable
internal fun JaNetworksScreen(
    repo: TmdbNetworksRepository,
    state: JaNetworksBrowseState,
    returnFocus: JaTitleReturnFocus,
    marked: (TitleItem) -> TitleItem,
    onOpen: (TitleItem) -> Unit,
    modifier: Modifier = Modifier, onBackToNavigation: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    suspend fun loadDirectory(force: Boolean) {
        if (state.loading) return
        state.loading = true; state.error = null
        try { state.directory = repo.directory(force) }
        catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { state.error = "Could not load TV networks. Select Retry." }
        finally { state.loading = false }
    }
    LaunchedEffect(repo) { loadDirectory(false) }
    val entries = state.directory?.networks.orEmpty().map { JaJumpRow(it.id.toString(), it.name) }
    JaRowJumpScrollEffect(state.jump, entries, state.listState)
    Column(modifier.jaCancelPendingRowJump(state.jump, returnFocus)) {
        Column(Modifier.jaWeatherIntroWidth()) {
        Text("TV Networks", style = MaterialTheme.typography.titleLarge, color = Color.White)
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        val notice = state.error ?: state.directory?.warning
        if (notice != null) {
            Text(notice, color = Color(0xFFFFA8A8), style = MaterialTheme.typography.bodySmall)
            TvFocusButton(onClick = { scope.launch { loadDirectory(true) } }, enabled = !state.loading) {
                Text(if (state.loading) "Loading…" else "Retry", color = Color.White)
            }
        }
        JaRowJumpButton(state.jump, entries, state.listState)
        Spacer(Modifier.height(10.dp))
        }
        LazyColumn(
            state = state.listState, modifier = Modifier.fillMaxWidth().weight(1f).jaWeatherContentStart(),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(state.directory?.networks.orEmpty(), key = { it.id }, contentType = { "network-row" }) { network ->
                JaNetworkRow(network, repo, state, returnFocus, marked, onOpen)
            }
        }
    }
    JaRowJumpPanel(state.jump, entries, networks = true, onBackToNavigation = onBackToNavigation)
}

@Composable
private fun JaNetworkRow(
    network: TmdbNetwork,
    repo: TmdbNetworksRepository,
    state: JaNetworksBrowseState,
    returnFocus: JaTitleReturnFocus,
    marked: (TitleItem) -> TitleItem,
    onOpen: (TitleItem) -> Unit
) {
    val scope = rememberCoroutineScope()
    val rowState = state.rowStates.getOrPut(network.id) { LazyListState() }
    var row by remember(repo, network.id) { mutableStateOf(repo.cachedRow(network.id)) }
    var loading by remember(repo, network.id) { mutableStateOf(false) }
    var error by remember(repo, network.id) { mutableStateOf<String?>(null) }
    fun titleKey(title: TitleItem) = "TV:Networks:${network.id}:tv:${title.id}"
    suspend fun load(more: Boolean) {
        if (loading) return
        loading = true; error = null
        val oldCount = row?.titles?.size ?: 0
        try {
            val loaded = repo.loadRow(network, more)
            row = loaded
            if (more && loaded.titles.size > oldCount) {
                withFrameNanos { }
                rowState.scrollToItem(oldCount)
                withFrameNanos { }
                returnFocus.focus(titleKey(loaded.titles[oldCount]))
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) {
            row = repo.cachedRow(network.id) ?: row
            error = "Could not load this network. Select Retry."
        } finally { loading = false }
    }
    // LazyColumn composes only visible/near-visible rows. Requests are cancelled
    // on disposal, globally limited to three and cached for returning to details.
    LaunchedEffect(repo, network.id) { if (row == null) load(false) }
    val emptyRequester = remember(network.id) { FocusRequester() }
    JaRowJumpTargetEffect(state.jump, network.id.toString(), rowState,
        row?.titles?.firstOrNull()?.let { titleKey(it) }, returnFocus, emptyRequester, loading)
    Column {
        JaRowJumpHeading(network.name, emptyRequester, row?.titles.isNullOrEmpty()) {
            state.jump.open(network.id.toString())
        }
        Spacer(Modifier.height(8.dp))
        val current = row
        if (current == null || current.titles.isEmpty()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 230.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (loading) "Loading shows…" else if (error != null) "Shows unavailable."
                    else "No shows listed for this network.", color = Color.LightGray)
                if (!loading && (error != null || current?.hasMore == true)) {
                    TvFocusButton(onClick = { scope.launch { load(current != null) } },
                        modifier = Modifier.jaOpenRowJump { state.jump.open(network.id.toString()) }) {
                        Text(if (error != null) "Retry" else "Load More", color = Color.White)
                    }
                }
            }
        } else {
            LazyRow(state = rowState, horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                items(current.titles, key = { it.id }, contentType = { "poster" }) { title ->
                    PosterTile(marked(title), returnFocus, titleKey(title),
                        modifier = if (title == current.titles.firstOrNull())
                            Modifier.jaRowJumpFirstTitle(state.jump, network.id.toString()) else Modifier
                    ) { onOpen(title) }
                }
                if (current.hasMore || error != null) item(key = "network-more-${network.id}") {
                    TvFocusButton(
                        onClick = { scope.launch { load(true) } }, enabled = !loading,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE5A43B))
                    ) {
                        Text(if (loading) "Loading…" else if (error != null) "Retry" else "Load More", color = Color.Black)
                    }
                }
            }
        }
        error?.let { Text(it, color = Color(0xFFFFA8A8), style = MaterialTheme.typography.bodySmall) }
    }
}
