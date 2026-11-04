package com.example.ja

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
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
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenRecommendation: (TitleItem) -> Unit,
    onOpenPerson: (TmdbPersonCredit) -> Unit,
    onBack: () -> Unit,
    backLabel: String,
    seasons: List<Int>,
    season: Int,
    episodes: List<EpisodeItem>,
    onSeasonSelected: (Int) -> Unit,
    onEpisodePlay: (EpisodeItem) -> Unit,
    restoreScrollY: Int? = null,
    onSaveScrollY: (Int) -> Unit = {}
) {
    val detailsScrollState = rememberScrollState()
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

    val title = details?.title ?: item.title
    val overview = details?.overview?.takeIf { it.isNotBlank() } ?: item.overview
    val posterUrl = details?.posterUrl ?: item.posterUrl
    val backdropUrl = details?.backdropUrl ?: item.backdropUrl
    val rating = details?.rating ?: item.rating.takeIf { it > 0 }

    // A visible Back button uses the same stack as Android system Back.
    // It also makes it obvious that the updated details screen is installed.
    Column(
        Modifier.fillMaxSize().background(detailsBg).verticalScroll(detailsScrollState),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            DetailsActionButton("← Back to $backLabel", onClick = onBack)
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 620.dp
            Column {
                Box(
                    Modifier.fillMaxWidth().height(if (compact) 120.dp else 175.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(detailsSurface)
                ) {
                    if (backdropUrl != null) {
                        AsyncImage(
                            model = backdropUrl, contentDescription = null,
                            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                        )
                    }
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                listOf(Color(0x770D1016), Color(0xBB12171D), detailsBg)
                            )
                        )
                    )
                    Text(
                        if (item.type == "tv") "TV SERIES" else "MOVIE",
                        color = detailsAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
                    )
                }

                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 24.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    AsyncImage(
                        model = posterUrl,
                        contentDescription = "$title poster",
                        modifier = Modifier.width(if (compact) 112.dp else 190.dp)
                            .height(if (compact) 167.dp else 282.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .border(1.dp, detailsSurface, RoundedCornerShape(9.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(
                            title, fontSize = if (compact) 25.sp else 35.sp,
                            lineHeight = if (compact) 29.sp else 40.sp,
                            color = Color.White, fontWeight = FontWeight.Bold,
                            maxLines = if (compact) 3 else 4, overflow = TextOverflow.Ellipsis
                        )
                        if (!details?.creatorNames.isNullOrBlank()) {
                            Text(
                                "${details?.creatorLabel}: ${details?.creatorNames}",
                                fontSize = 13.sp, color = detailsMuted,
                                maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (!details?.tagline.isNullOrBlank()) {
                            Text(
                                details!!.tagline.orEmpty(), color = detailsMuted,
                                fontSize = 13.sp, maxLines = if (compact) 3 else 4,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (!compact) {
                            DetailsMetaLine(details)
                            DetailsScore(rating, details?.voteCount ?: 0)
                        }
                    }
                }
                if (compact) {
                    Spacer(Modifier.height(12.dp))
                    DetailsMetaLine(details)
                    DetailsScore(rating, details?.voteCount ?: 0)
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DetailsActionButton("▶  Play", primary = true, onClick = onPlay)
            DetailsActionButton(if (isFavorite) "★  Favorited" else "☆  Favorite", onClick = onToggleFavorite)
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

        if (item.type == "tv") {
            DetailsHeading("Seasons & Episodes")
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
                Spacer(Modifier.height(4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(episodes, key = { it.episode }) { ep ->
                        DetailsEpisodeCard(ep, onClick = { onEpisodePlay(ep) })
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
                            suggested.title, color = Color.White, fontSize = 12.sp,
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
private fun DetailsActionButton(text: String, primary: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) detailsAccent else detailsSurface,
            contentColor = if (primary) Color.Black else Color.White
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier.border(3.dp, Color.White, RoundedCornerShape(12.dp)) else Modifier)
    ) {
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DetailsEpisodeCard(ep: EpisodeItem, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier.width(190.dp).heightIn(min = 92.dp)
            .clip(RoundedCornerShape(12.dp)).background(detailsSurface)
            .onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier.border(3.dp, Color.White, RoundedCornerShape(12.dp)) else Modifier)
            .clickable(onClick = onClick).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("S${ep.season} E${ep.episode}", color = detailsAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(ep.name, color = Color.White, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text("▶ Play episode", color = detailsMuted, fontSize = 11.sp)
    }
}
