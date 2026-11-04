package com.example.ja

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

private val searchAmber = Color(0xFFE5A43B)
private val searchPanel = Color(0xFF202832)
private val searchMuted = Color(0xFFB5BEC6)

/** Search results are hoisted by MainActivity so returning from a title/person retains the query. */
@Composable
fun JaSearchScreen(
    query: String,
    gridState: LazyGridState,
    onQueryChange: (String) -> Unit,
    mode: JaSearchMode,
    onModeChange: (JaSearchMode) -> Unit,
    onSearch: () -> Unit,
    loading: Boolean,
    hasSearched: Boolean,
    error: String?,
    results: JaSearchResponse,
    selectedGenre: JaSearchGenre?,
    genreTitles: List<TitleItem>,
    genreLoading: Boolean,
    onOpenGenre: (JaSearchGenre) -> Unit,
    onCloseGenre: () -> Unit,
    onOpenTitle: (TitleItem) -> Unit,
    onOpenPerson: (TmdbPersonCredit) -> Unit
) {
    // A single vertically scrolling grid avoids nested scrolling containers. On phones
    // it normally shows two posters per row; on TVs it expands to use the wider screen.
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 152.dp),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(start = 2.dp, end = 2.dp, bottom = 24.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "search-heading") {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Search", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Find movies, TV shows, actors, directors and genres", color = searchMuted, fontSize = 13.sp)
            }
        }
        // Search filters remain a compact horizontal strip. Only result cards scroll vertically.
        item(span = { GridItemSpan(maxLineSpan) }, key = "search-filters") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowItems(JaSearchMode.entries) { candidate ->
                    JaSearchButton(
                        text = candidate.label,
                        highlighted = candidate == mode,
                        onClick = { onModeChange(candidate) }
                    )
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }, key = "search-form") {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedTextField(
                    value = query, onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (mode == JaSearchMode.GENRES) "Filter genres (e.g. Horror)" else "Title, actor or director") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = searchAmber,
                        focusedLabelColor = searchAmber
                    )
                )
                JaSearchButton(
                    text = if (mode == JaSearchMode.GENRES) "Browse genres" else "Search TMDB",
                    highlighted = true,
                    onClick = onSearch
                )
            }
        }
        if (loading || genreLoading) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "search-progress") {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = searchAmber)
            }
        }
        error?.let { message ->
            item(span = { GridItemSpan(maxLineSpan) }, key = "search-error") {
                Text(message, color = Color(0xFFFFB4AB))
            }
        }

        if (selectedGenre != null) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "selected-genre-heading") {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    JaSearchButton("← Back to search", false, onCloseGenre)
                    Text("${selectedGenre.name} · ${selectedGenre.displayType}",
                        color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (!genreLoading && genreTitles.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "genre-empty") {
                    Text("No titles found in this genre.", color = searchMuted)
                }
            }
            if (!genreLoading) {
                items(genreTitles, key = { "genre-title-${it.type}-${it.id}" }) { title ->
                    JaSearchTitleCard(title) { onOpenTitle(title) }
                }
            }
        } else {
            val movieResults = results.titles.filter { it.type == "movie" }
            val tvResults = results.titles.filter { it.type == "tv" }
            if (movieResults.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "movie-section") {
                    JaSearchSection("Movies", movieResults.size)
                }
                items(movieResults, key = { "movie-${it.id}" }) { title ->
                    JaSearchTitleCard(title) { onOpenTitle(title) }
                }
            }
            if (tvResults.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "tv-section") {
                    JaSearchSection("TV Shows", tvResults.size)
                }
                items(tvResults, key = { "tv-${it.id}" }) { title ->
                    JaSearchTitleCard(title) { onOpenTitle(title) }
                }
            }
            if (results.people.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "people-section") {
                    JaSearchSection(
                        when (mode) {
                            JaSearchMode.ACTORS -> "Actors"
                            JaSearchMode.DIRECTORS -> "Director matches"
                            else -> "People"
                        },
                        results.people.size
                    )
                }
                items(results.people, key = { "person-${it.credit.id}" }) { person ->
                    JaSearchPersonCard(person) { onOpenPerson(person.credit) }
                }
            }
            if (results.genres.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "genres-section") {
                    JaSearchSection("Genres", results.genres.size)
                }
                items(results.genres, key = { "genre-${it.type}-${it.id}" }) { genre ->
                    JaSearchButton(
                        "${genre.name} · ${genre.displayType}", false,
                        onClick = { onOpenGenre(genre) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            if (!loading && hasSearched && error == null &&
                results.titles.isEmpty() && results.people.isEmpty() && results.genres.isEmpty()
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "search-no-results") {
                    Text("No results from TMDB. Try another name or search category.", color = searchMuted)
                }
            }
            if (!loading && !hasSearched) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "search-hint") {
                    Text(
                        if (mode == JaSearchMode.GENRES) "Choose Browse genres to see TMDB's movie and TV categories."
                        else "Enter a name and press Search. Select a person to see their biography and filmography.",
                        color = searchMuted, fontSize = 14.sp
                    )
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }, key = "search-attribution") {
            Text("This product uses the TMDB API but is not endorsed or certified by TMDB.",
                color = searchMuted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun JaSearchSection(text: String, count: Int) {
    Text("$text ($count)", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun JaSearchButton(text: String, highlighted: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (highlighted) searchAmber else searchPanel,
            contentColor = if (highlighted) Color.Black else Color.White
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier.border(3.dp, Color.White, RoundedCornerShape(12.dp)) else Modifier),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 11.dp)
    ) {
        Text(text, maxLines = 2, fontSize = 13.sp)
    }
}

@Composable
private fun JaSearchTitleCard(title: TitleItem, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier.border(3.dp, Color.White, RoundedCornerShape(9.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        AsyncImage(
            model = title.posterUrl,
            contentDescription = title.title,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                .background(searchPanel, RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop
        )
        Text(title.title, color = Color.White, fontSize = 13.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun JaSearchPersonCard(person: JaSearchPerson, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier.border(3.dp, Color.White, RoundedCornerShape(10.dp)) else Modifier)
            .clickable(onClick = onClick).padding(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        AsyncImage(
            model = person.credit.profileUrl, contentDescription = "Profile of ${person.credit.name}",
            modifier = Modifier.size(112.dp).background(searchPanel, RoundedCornerShape(56.dp)),
            contentScale = ContentScale.Crop
        )
        Text(person.credit.name, color = Color.White, fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        person.department?.let {
            Text(it, color = searchAmber, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
        person.knownFor?.let {
            Text(it, color = searchMuted, fontSize = 11.sp, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
