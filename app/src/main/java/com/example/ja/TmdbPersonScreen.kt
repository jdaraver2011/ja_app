package com.example.ja

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException

// Match JA's existing details-page palette; independent of the GeckoView player.
private val personBackground = Color(0xFF12171D)
private val personAccent = Color(0xFFE5A43B)
private val personSurface = Color(0xFF202832)
private val personMuted = Color(0xFFB5BEC6)

@Composable
fun TmdbPersonScreen(
    person: TmdbPersonCredit,
    repository: TmdbPersonRepository,
    onBack: () -> Unit,
    onOpenTitle: (TitleItem) -> Unit,
    backLabel: String = "title"
) {
    var personInfo by remember(person.id) { mutableStateOf<TmdbPersonDetails?>(null) }
    var loading by remember(person.id) { mutableStateOf(true) }
    var error by remember(person.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(person.id) {
        loading = true
        error = null
        personInfo = null
        try { personInfo = repository.person(person) }
        catch (cancel: CancellationException) { throw cancel }
        catch (e: Exception) { error = e.message ?: "Person details unavailable" }
        finally { loading = false }
    }

    LazyColumn(
        Modifier.fillMaxSize().background(personBackground),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { PersonBackButton(onClick = onBack, label = backLabel) }

        item { BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 620.dp
            Row(horizontalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 24.dp)) {
                AsyncImage(
                    model = personInfo?.profileUrl ?: person.profileUrl,
                    contentDescription = "Portrait of ${personInfo?.name ?: person.name}",
                    modifier = Modifier.width(if (compact) 116.dp else 200.dp)
                        .height(if (compact) 172.dp else 300.dp)
                        .clip(RoundedCornerShape(12.dp)).background(personSurface),
                    contentScale = ContentScale.Crop
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        personInfo?.name ?: person.name, color = Color.White,
                        fontSize = if (compact) 25.sp else 38.sp,
                        lineHeight = if (compact) 29.sp else 43.sp,
                        fontWeight = FontWeight.Bold
                    )
                    personInfo?.knownFor?.let { Text(it, color = personAccent, fontWeight = FontWeight.SemiBold) }
                    personInfo?.birthday?.let { PersonFact("Born", it) }
                    personInfo?.deathday?.let { PersonFact("Died", it) }
                    personInfo?.placeOfBirth?.let { PersonFact("Birthplace", it) }
                }
            }
        } }

        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = personAccent) }
        error?.let { message -> item { Text("TMDB person details unavailable: $message", color = personMuted) } }

        if (!loading && personInfo != null) {
            item { Text("Biography", fontSize = 22.sp, color = Color.White, fontWeight = FontWeight.SemiBold) }
            item {
                Text(
                    personInfo?.biography ?: "No biography is currently available from TMDB.",
                    color = Color.White, fontSize = 15.sp, lineHeight = 23.sp
                )
            }
            if (!personInfo?.alsoKnownAs.isNullOrEmpty()) {
                item {
                    Text("Also known as: ${personInfo!!.alsoKnownAs.joinToString(", ")}",
                        color = personMuted, fontSize = 12.sp, lineHeight = 18.sp)
                }
            }

            if (!personInfo?.knownForTitles.isNullOrEmpty()) {
                item { Text("Known For", fontSize = 22.sp, color = Color.White, fontWeight = FontWeight.SemiBold) }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(personInfo!!.knownForTitles, key = { "${it.title.type}:${it.title.id}" }) { credit ->
                            PersonKnownForCard(credit, onClick = { onOpenTitle(credit.title) })
                        }
                    }
                }
            }

            if (!personInfo?.filmography.isNullOrEmpty()) {
                val credits = personInfo!!.filmography.take(50)
                item {
                    Text(
                        "Filmography${if (personInfo!!.filmography.size > 50) " (first 50 of ${personInfo!!.filmography.size})" else ""}",
                        fontSize = 22.sp, color = Color.White, fontWeight = FontWeight.SemiBold
                    )
                }
                items(credits, key = { "${it.title.type}:${it.title.id}" }) { credit ->
                    PersonFilmographyRow(credit, onClick = { onOpenTitle(credit.title) })
                }
            }
        }
        item {
            Text(
                "This product uses the TMDB API but is not endorsed or certified by TMDB.",
                color = personMuted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 20.dp)
            )
        }
    }
}

@Composable
private fun PersonFact(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, fontSize = 12.sp, color = personMuted)
        Text(value, fontSize = 14.sp, color = Color.White)
    }
}

@Composable
private fun PersonBackButton(onClick: () -> Unit, label: String) {
    var focused by remember { mutableStateOf(false) }
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = personSurface, contentColor = Color.White),
        modifier = Modifier.onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier.border(3.dp, Color.White, RoundedCornerShape(12.dp)) else Modifier),
        shape = RoundedCornerShape(12.dp)
    ) { Text("← Back to $label") }
}

@Composable
private fun PersonKnownForCard(credit: TmdbPersonFilmCredit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier.width(130.dp)
            .onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier.border(3.dp, Color.White, RoundedCornerShape(9.dp)) else Modifier)
            .clickable(onClick = onClick).padding(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AsyncImage(
            model = credit.title.posterUrl, contentDescription = credit.title.title,
            modifier = Modifier.fillMaxWidth().height(185.dp)
                .clip(RoundedCornerShape(7.dp)).background(personSurface),
            contentScale = ContentScale.Crop
        )
        Text(credit.title.title, color = Color.White, fontSize = 12.sp, maxLines = 2,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 5.dp))
        credit.year?.let { Text(it, color = personMuted, fontSize = 11.sp) }
    }
}

@Composable
private fun PersonFilmographyRow(credit: TmdbPersonFilmCredit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(9.dp)) else Modifier)
            .clip(RoundedCornerShape(9.dp))
            .background(personSurface)
            .clickable(onClick = onClick).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = credit.title.posterUrl, contentDescription = null,
            modifier = Modifier.size(width = 43.dp, height = 64.dp)
                .clip(RoundedCornerShape(5.dp)).background(personBackground),
            contentScale = ContentScale.Crop
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(credit.title.title, color = Color.White, fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(credit.year, credit.title.type.uppercase(), credit.role)
                    .joinToString("  •  "),
                color = personMuted, fontSize = 12.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
