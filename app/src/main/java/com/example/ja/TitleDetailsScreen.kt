package com.example.ja

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.Locale

// JA's existing palette. This page is separate from the GeckoView/remote player.
private val detailsBg = Color(0xFF12171D)
private val detailsAccent = Color(0xFFE5A43B)
private val detailsSurface = Color(0xFF202832)
private val detailsMuted = Color(0xFFB5BEC6)

@Composable
fun TitleDetailsScreen(
    item: TitleItem,
    repo: TmdbDetailsRepository,
    isFavorite: Boolean,
    watchedKeys: Set<String> = emptySet(),
    pendingEpisodeFocus: Pair<Int, Int>? = null,
    onEpisodeFocusRestored: () -> Unit = {},
    onSetWatched: (Int, Int, Boolean) -> Unit = { _, _, _ -> },
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenRecommendation: (TitleItem) -> Unit,
    onOpenPerson: (TmdbPersonCredit) -> Unit,
    onBack: () -> Unit,
    seasons: List<Int>,
    season: Int,
    episodes: List<EpisodeItem>,
    onSeasonSelected: (Int) -> Unit,
    onEpisodePlay: (EpisodeItem) -> Unit,
    restoreScrollY: Int? = null,
    onSaveScrollY: (Int) -> Unit = {}
) {
    val detailsScrollState = rememberScrollState()
    val episodeRow = rememberLazyListState()
    val episodeFocus = remember { FocusRequester() }
    val watchedMovie = WatchedStore.key(item.id, item.type) in watchedKeys
    val nextEpisode = nextUnwatchedEpisode(item.id, episodes, watchedKeys)
    DisposableEffect(item.type, item.id) {
        onDispose { onSaveScrollY(detailsScrollState.value) }
    }
    var details by remember(item.id, item.type) { mutableStateOf<TmdbTitleDetails?>(null) }
    var detailsLoading by remember(item.id, item.type) { mutableStateOf(true) }
    var detailsError by remember(item.id, item.type) { mutableStateOf<String?>(null) }

    LaunchedEffect(item.id, item.type) {
        detailsLoading = true
        detailsError = null
        try { details = repo.details(item) }
        catch (cancel: CancellationException) { throw cancel }
        catch (e: Exception) { detailsError = e.message ?: "Details unavailable" }
        finally { detailsLoading = false }
    }

    LaunchedEffect(item.id, item.type, detailsLoading) {
        if (!detailsLoading && restoreScrollY != null) {
            withFrameNanos { }
            detailsScrollState.scrollTo(restoreScrollY)
        }
    }

    LaunchedEffect(pendingEpisodeFocus, episodes, detailsLoading) {
        val target = pendingEpisodeFocus ?: return@LaunchedEffect
        if (detailsLoading) return@LaunchedEffect
        val index = episodes.indexOfFirst { it.season == target.first && it.episode == target.second }
        if (index >= 0) {
            episodeRow.scrollToItem(index)
            snapshotFlow { episodeRow.layoutInfo.visibleItemsInfo.any { it.index == index } }.first { it }
            withFrameNanos { }
            episodeFocus.requestFocus()
            onEpisodeFocusRestored()
        }
    }

    val title = details?.title ?: item.title
    val overview = details?.overview?.takeIf { it.isNotBlank() } ?: item.overview
    val posterUrl = details?.posterUrl ?: item.posterUrl
    val rating = details?.rating ?: item.rating.takeIf { it > 0 }

    // A visible Back button uses the same stack as Android system Back.
    // It also makes it obvious that the updated details screen is installed.
    Column(
        Modifier.fillMaxSize().background(detailsBg).verticalScroll(detailsScrollState),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            JaCompactBackButton(onClick = onBack)
        }
        // Compact header for both movies and TV shows. The oversized backdrop
        // has been removed to bring Overview (and TV seasons) into view sooner.
        // Poster is about 25% smaller than the previous details poster.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 620.dp
            val posterWidth = if (compact) 84.dp else 144.dp
            val posterHeight = if (compact) 126.dp else 212.dp
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 22.dp),
                verticalAlignment = Alignment.Top
            ) {
                AsyncImage(
                    model = posterUrl,
                    contentDescription = "$title poster",
                    modifier = Modifier.width(posterWidth).height(posterHeight)
                        .clip(RoundedCornerShape(9.dp))
                        .border(1.dp, detailsSurface, RoundedCornerShape(9.dp)),
                    contentScale = ContentScale.Crop
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)
                ) {
                    Text(
                        title,
                        fontSize = if (compact) 22.sp else 31.sp,
                        lineHeight = if (compact) 26.sp else 35.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines = if (compact) 3 else 4,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!details?.creatorNames.isNullOrBlank()) {
                        Text(
                            "${details?.creatorLabel}: ${details?.creatorNames}",
                            fontSize = 13.sp,
                            color = detailsMuted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (!details?.tagline.isNullOrBlank()) {
                        Text(
                            details!!.tagline.orEmpty(),
                            color = detailsMuted,
                            fontSize = 13.sp,
                            maxLines = if (compact) 2 else 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    DetailsMetaLine(details)
                    DetailsScore(rating, details?.voteCount ?: 0)
                    // Keep both actions directly below the TMDB score, beside
                    // the poster instead of below the entire details header.
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DetailsActionButton(
                            if (item.type == "movie") "▶  Play" else nextEpisode?.let { "▶ S${it.season} E${it.episode}" }
                                ?: if (episodes.isEmpty()) "Loading episodes…" else "✓ Season watched",
                            primary = true, compact = compact,
                            enabled = item.type == "movie" || nextEpisode != null, onClick = onPlay)
                        if (item.type == "movie") DetailsActionButton(
                            if (watchedMovie) "✓ Watched · Mark unwatched" else "Mark watched",
                            compact = compact, onClick = { onSetWatched(0, 0, !watchedMovie) })
                        DetailsActionButton(
                            if (isFavorite) "★  Favorited" else "☆  Favorite",
                            compact = compact,
                            onClick = onToggleFavorite
                        )
                    }
                }
            }
        }

        if (detailsLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = detailsAccent)
        } else if (detailsError != null) {
            Text("Extended TMDB details unavailable: $detailsError", color = detailsMuted, fontSize = 12.sp)
        }

        if (overview.isNotBlank()) {
            DetailsHeading("Overview")
            Text(overview, color = Color.White, fontSize = 15.sp, lineHeight = 23.sp)
        }

        if (item.type == "tv") {
            DetailsHeading("Seasons & Episodes")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (seasons.isEmpty()) {
                    Text("Loading seasons…", color = detailsMuted, fontSize = 13.sp)
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(seasons) { candidate ->
                            DetailsActionButton(
                                "Season $candidate", primary = candidate == season,
                                onClick = { onSeasonSelected(candidate) }
                            )
                        }
                    }
                }
                if (episodes.isNotEmpty()) {
                    LazyRow(state = episodeRow, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(episodes, key = { "${it.season}:${it.episode}" }) { ep ->
                            val watched = WatchedStore.key(item.id, "tv", ep.season, ep.episode) in watchedKeys
                            DetailsEpisodeCard(ep, watched,
                                modifier = if (pendingEpisodeFocus == (ep.season to ep.episode)) Modifier.focusRequester(episodeFocus) else Modifier,
                                onSetWatched = { onSetWatched(ep.season, ep.episode, !watched) },
                                onClick = { onEpisodePlay(ep) })
                        }
                    }
                }
            }
        }

        if (!details?.cast.isNullOrEmpty()) {
            DetailsHeading("Cast & Crew")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(details!!.cast, key = { it.id }) { person ->
                    var focused by remember { mutableStateOf(false) }
                    Column(
                        Modifier.width(100.dp)
                            .onFocusChanged { focused = it.isFocused }
                            .then(if (focused) Modifier.border(3.dp, Color.White, RoundedCornerShape(12.dp)) else Modifier)
                            .clickable { onOpenPerson(person) }
                            .padding(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            Modifier.size(82.dp).clip(CircleShape).background(detailsSurface),
                            contentAlignment = Alignment.Center
                        ) {
                            if (person.profileUrl != null) {
                                AsyncImage(
                                    model = person.profileUrl,
                                    contentDescription = person.name,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Text(person.name.take(1), fontSize = 25.sp, color = detailsAccent)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            person.name, color = Color.White, fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                        if (person.role.isNotBlank()) Text(
                            person.role, color = detailsMuted, fontSize = 11.sp,
                            textAlign = TextAlign.Center, maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        if (!details?.recommendations.isNullOrEmpty()) {
            DetailsHeading("More Like This")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(details!!.recommendations, key = { it.id }) { suggested ->
                    var focused by remember { mutableStateOf(false) }
                    Column(
                        Modifier.width(118.dp)
                            .onFocusChanged { focused = it.isFocused }
                            .then(if (focused) Modifier.border(3.dp, Color.White, RoundedCornerShape(9.dp)) else Modifier)
                            .clickable { onOpenRecommendation(suggested) }
                            .padding(3.dp)
                    ) {
                        AsyncImage(
                            model = suggested.posterUrl, contentDescription = suggested.title,
                            modifier = Modifier.fillMaxWidth().height(165.dp)
                                .clip(RoundedCornerShape(6.dp)).background(detailsSurface),
                            contentScale = ContentScale.Crop
                        )
                        Text(
                            (if (WatchedStore.key(suggested.id, suggested.type) in watchedKeys) "✓ " else "") + suggested.title, color = Color.White, fontSize = 12.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 5.dp)
                        )
                    }
                }
            }
        }
        Text(
            "This product uses the TMDB API but is not endorsed or certified by TMDB.",
            color = detailsMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 10.dp, bottom = 18.dp)
        )
    }
}

@Composable
private fun DetailsHeading(text: String) {
    Text(text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
}

@Composable
private fun DetailsMetaLine(details: TmdbTitleDetails?) {
    if (details == null) return
    val chunks = buildList {
        details.certification?.let { add(it) }
        details.releaseYear?.let { add(it) }
        details.runtimeMinutes?.let { minutes ->
            if (minutes > 0) add(if (details.isTv) "$minutes min/ep" else "${minutes / 60}h ${minutes % 60}m".takeIf { minutes >= 60 } ?: "$minutes min")
        }
        if (details.isTv) add("TV")
        details.genres.take(3).forEach { add(it) }
    }
    if (chunks.isNotEmpty()) {
        Text(
            chunks.joinToString("  •  "), color = detailsMuted,
            fontSize = 13.sp, lineHeight = 19.sp
        )
    }
}

@Composable
private fun DetailsScore(rating: Double?, voteCount: Int) {
    if (rating == null) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("TMDB", color = detailsAccent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("${String.format(Locale.US, "%.1f", rating)} / 10", color = Color.White, fontWeight = FontWeight.Bold)
        if (voteCount > 0) Text("(${String.format(Locale.US, "%,d", voteCount)} votes)", color = detailsMuted, fontSize = 12.sp)
    }
}

@Composable
private fun DetailsActionButton(
    text: String,
    primary: Boolean = false,
    compact: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) detailsAccent else detailsSurface,
            contentColor = if (primary) Color.Black else Color.White
        ),
        border = jaButtonFocusBorder(focused),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(
            horizontal = if (compact) 9.dp else 16.dp,
            vertical = if (compact) 6.dp else 9.dp
        ),
        modifier = Modifier.onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier.border(3.dp, Color.White, RoundedCornerShape(12.dp)) else Modifier)
    ) {
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = if (compact) 12.sp else 14.sp, maxLines = 1)
    }
}

@Composable
private fun DetailsEpisodeCard(ep: EpisodeItem, watched: Boolean, modifier: Modifier,
    onSetWatched: () -> Unit, onClick: () -> Unit) {
    var playFocused by remember { mutableStateOf(false) }
    var watchedFocused by remember { mutableStateOf(false) }
    val playShape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)
    val watchedShape = RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp)
    Column(Modifier.width(168.dp).clip(RoundedCornerShape(10.dp)).background(detailsSurface)) {
        Column(
            modifier.fillMaxWidth().heightIn(min = 64.dp).clip(playShape)
                .onFocusChanged { playFocused = it.isFocused }
                .then(if (playFocused) Modifier.border(2.dp, Color.White, playShape) else Modifier)
                .clickable(role = Role.Button, onClick = onClick).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text("${if (watched) "✓ " else ""}S${ep.season} E${ep.episode}", color = detailsAccent,
                fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(ep.name, color = Color.White, fontSize = 13.sp, minLines = 2, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
        }
        HorizontalDivider(color = detailsMuted.copy(alpha = 0.18f))
        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).clip(watchedShape)
            .onFocusChanged { watchedFocused = it.isFocused }
            .then(if (watchedFocused) Modifier.border(2.dp, Color.White, watchedShape) else Modifier)
            .clickable(role = Role.Button, onClick = onSetWatched)
            .padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (watched) "Mark unwatched" else "Mark watched", color = Color.White,
                fontSize = 11.sp, maxLines = 1)
        }
    }
}
