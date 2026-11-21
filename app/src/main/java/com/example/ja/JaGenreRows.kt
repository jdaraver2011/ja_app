package com.example.ja

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
internal fun JaGenreRows(
    tab: String, rows: List<GenreRow>, loading: Boolean,
    sectionState: LazyListState, rowStates: MutableMap<String, LazyListState>,
    rowLoading: Map<String, Boolean>, rowErrors: Map<String, String>,
    returnFocus: JaTitleReturnFocus, jump: JaRowJumpState,
    visibleTitles: (List<TitleItem>) -> List<TitleItem>, marked: (TitleItem) -> TitleItem,
    onOpen: (TitleItem) -> Unit, onLoadMore: (GenreRow) -> Unit,
    modifier: Modifier = Modifier, onBackToNavigation: () -> Unit = {}
) {
    val entries = rows.map { JaJumpRow(it.genreId.toString(), it.name) }
    JaRowJumpScrollEffect(jump, entries, sectionState)
    Column(modifier.jaCancelPendingRowJump(jump, returnFocus)) {
        Column(Modifier.jaWeatherIntroWidth()) {
        JaRowJumpButton(jump, entries, sectionState)
        Spacer(Modifier.height(8.dp))
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Loading genre rows…", color = Color.LightGray)
        }
        }
        LazyColumn(state = sectionState, modifier = Modifier.fillMaxWidth().weight(1f).jaWeatherContentStart(),
            verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(rows, key = { it.genreId }) { row ->
                val titles = remember(row.titles,visibleTitles) { visibleTitles(row.titles) }
                val rowId = row.genreId.toString()
                val rowState = rowStates.getOrPut("$tab:${row.name}") { LazyListState() }
                val emptyRequester = remember(rowId) { FocusRequester() }
                val type = if (tab == "Movies") "movie" else "tv"
                val loadingMore = rowLoading["$type:${row.genreId}"] == true
                fun titleKey(title: TitleItem) = "$tab:Genres:${row.name}:${title.type}:${title.id}"
                JaRowJumpTargetEffect(jump, rowId, rowState, titles.firstOrNull()?.let { titleKey(it) },
                    returnFocus, emptyRequester, loading || loadingMore)
                Column {
                    JaRowJumpHeading(row.name, emptyRequester, titles.isEmpty()) { jump.open(rowId) }
                    Spacer(Modifier.height(8.dp))
                    LazyRow(state = rowState, horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        items(titles, key = { "${it.type}-${it.id}" }) { item ->
                            PosterTile(marked(item), returnFocus, titleKey(item),
                                modifier = if (item == titles.firstOrNull())
                                    Modifier.jaRowJumpFirstTitle(jump, rowId) else Modifier
                            ) { onOpen(item) }
                        }
                        if (row.hasMore) item(key = "genre-more-${row.genreId}") {
                            TvFocusButton(onClick = { onLoadMore(row) },
                                modifier = if (titles.isEmpty()) Modifier.jaOpenRowJump { jump.open(rowId) } else Modifier,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE5A43B)), enabled = !loading) {
                                Text(if (loadingMore) "Loading…" else if (rowErrors.containsKey("$type:${row.genreId}"))
                                    "Retry" else "Load More", color = Color.Black)
                            }
                        }
                    }
                    rowErrors["$type:${row.genreId}"]?.let {
                        Text(it, color = Color(0xFFFFA8A8), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    JaRowJumpPanel(jump, entries, networks = false, onBackToNavigation = onBackToNavigation)
}
