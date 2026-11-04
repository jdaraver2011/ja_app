package com.example.ja

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(15.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Text("Search", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("Find movies, TV shows, actors, directors and genres", color = searchMuted, fontSize = 13.sp)
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(JaSearchMode.entries) { candidate ->
                    JaSearchButton(
                        text = candidate.label,
                        highlighted = candidate == mode,
                        onClick = { onModeChange(candidate) }
                    )
                }
            }
        }
        item {
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
            item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = searchAmber) }
        }
        error?.let { message -> item { Text(message, color = Color(0xFFFFB4AB)) } }

        if (selectedGenre != null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    JaSearchButton("← Back to search", false, onCloseGenre)
                    Text("${selectedGenre.name} · ${selectedGenre.displayType}",
                        color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (!genreLoading && genreTitles.isEmpty()) {
                item { Text("No titles found in this genre.", color = searchMuted) }
            }
            if (!genreLoading && genreTitles.isNotEmpty()) {
                item { JaSearchTitleRow(genreTitles, onOpenTitle) }
            }
        } else {
            val movieResults = results.titles.filter { it.type == "movie" }
            val tvResults = results.titles.filter { it.type == "tv" }
            if (movieResults.isNotEmpty()) {
                item { JaSearchSection("Movies", movieResults.size) }
                item { JaSearchTitleRow(movieResults, onOpenTitle) }
            }
            if (tvResults.isNotEmpty()) {
                item { JaSearchSection("TV Shows", tvResults.size) }
                item { JaSearchTitleRow(tvResults, onOpenTitle) }
            }
            if (results.people.isNotEmpty()) {
                item {
                    JaSearchSection(
                        when (mode) {
                            JaSearchMode.ACTORS -> "Actors"
                            JaSearchMode.DIRECTORS -> "Director matches"
                            else -> "People"
                        },
                        results.people.size
                    )
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(results.people, key = { it.credit.id }) { person ->
                            JaSearchPersonCard(person) { onOpenPerson(person.credit) }
                        }
                    }
                }
            }
            if (results.genres.isNotEmpty()) {
                item { JaSearchSection("Genres", results.genres.size) }
                items(results.genres.chunked(2)) { genrePair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        genrePair.forEach { genre ->
                            Box(Modifier.weight(1f)) {
                                JaSearchButton("${genre.name} · ${genre.displayType}", false,
                                    onClick = { onOpenGenre(genre) })
                            }
                        }
                        if (genrePair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            if (!loading && hasSearched && error == null &&
                results.titles.isEmpty() && results.people.isEmpty() && results.genres.isEmpty()
            ) {
                item { Text("No results from TMDB. Try another name or search category.", color = searchMuted) }
            }
            if (!loading && !hasSearched) {
                item {
                    Text(
                        if (mode == JaSearchMode.GENRES) "Choose Browse genres to see TMDB's movie and TV categories."
                        else "Enter a name and press Search. Select a person to see their biography and filmography.",
                        color = searchMuted, fontSize = 14.sp
                    )
                }
            }
        }
        item {
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
private fun JaSearchButton(text: String, highlighted: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (highlighted) searchAmber else searchPanel,
            contentColor = if (highlighted) Color.Black else Color.White
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier.border(3.dp, Color.White, RoundedCornerShape(12.dp)) else Modifier),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 11.dp)
    ) {
        Text(text, maxLines = 2, fontSize = 13.sp)
    }
}

@Composable
private fun JaSearchTitleRow(titles: List<TitleItem>, onClick: (TitleItem) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(titles, key = { "${it.type}-${it.id}" }) { title ->
            var focused by remember { mutableStateOf(false) }
            Column(
                modifier = Modifier.width(133.dp)
                    .onFocusChanged { focused = it.isFocused }
                    .then(if (focused) Modifier.border(3.dp, Color.White, RoundedCornerShape(9.dp)) else Modifier)
                    .clickable { onClick(title) }
                    .padding(3.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AsyncImage(model = title.posterUrl, contentDescription = title.title,
                    modifier = Modifier.fillMaxWidth().height(192.dp)
                        .background(searchPanel, RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop)
                Spacer(Modifier.height(5.dp))
                Text(title.title, color = Color.White, fontSize = 12.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun JaSearchPersonCard(person: JaSearchPerson, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.width(156.dp)
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
