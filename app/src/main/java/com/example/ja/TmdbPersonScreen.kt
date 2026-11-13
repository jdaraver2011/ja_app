package com.example.ja

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.flow.first
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

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
    filmographyPage: Int = 0,
    onFilmographyPageChange: (Int) -> Unit = {},
    restoreScrollPosition: Pair<Int, Int>? = null,
    onSaveScrollPosition: (Int, Int) -> Unit = { _, _ -> }
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scrollScope = rememberCoroutineScope()
    val backFocus = remember(person.id) { FocusRequester() }
    val biographyFocus = remember(person.id) { FocusRequester() }
    val contentFocus = remember(person.id) { FocusRequester() }
    // Save on navigation away; restoration waits until TMDB details are loaded.
    DisposableEffect(person.id) {
        onDispose {
            onSaveScrollPosition(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }
    }
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

    LaunchedEffect(person.id, personInfo?.id) {
        if (personInfo != null && restoreScrollPosition != null) {
            // Re-fetching TMDB details initially displays a shorter loading page;
            // restoring before the loaded content is laid out would clamp to top.
            withFrameNanos { }
            listState.scrollToItem(restoreScrollPosition.first, restoreScrollPosition.second)
        }
    }

    val movePastBiography: () -> Unit = {
        val info = personInfo
        if (info != null) scrollScope.launch {
            val prefix = 3 + (if (error != null) 1 else 0) + (if (info.alsoKnownAs.isNotEmpty()) 1 else 0)
            if (info.knownForTitles.isNotEmpty()) {
                listState.scrollToItem(prefix)
                snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.key == "known-for-row" } }.first { it }
                withFrameNanos { }
                contentFocus.requestFocus()
            } else if (info.filmography.isNotEmpty()) {
                val pages = (info.filmography.size + 49) / 50
                val page = filmographyPage.coerceIn(0, pages - 1)
                val firstCredit = info.filmography[page * 50]
                val firstKey = "${firstCredit.title.type}:${firstCredit.title.id}"
                listState.scrollToItem(prefix + 1 + (if (pages > 1) 1 else 0))
                snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.key == firstKey } }.first { it }
                withFrameNanos { }
                contentFocus.requestFocus()
            }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().background(personBackground),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "back") {
            JaCompactBackButton(onClick = onBack, modifier = Modifier.focusRequester(backFocus)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown && !loading && personInfo != null) {
                        scrollScope.launch {
                            val biographyIndex = 2 + (if (error != null) 1 else 0)
                            listState.scrollToItem(biographyIndex)
                            snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.key == "biography" } }.first { it }
                            withFrameNanos { }
                            biographyFocus.requestFocus()
                        }
                        true
                    } else false
                })
        }

        item { BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 620.dp
            Row(horizontalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 24.dp)) {
                AsyncImage(
                    model = personInfo?.profileUrl ?: person.profileUrl,
                    contentDescription = "Portrait of ${personInfo?.name ?: person.name}",
                    modifier = Modifier.width(if (compact) 84.dp else 144.dp)
                        .height(if (compact) 126.dp else 212.dp)
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
            item(key = "biography") {
                PersonBiography(
                    text = personInfo?.biography ?: "No biography is currently available from TMDB.",
                    requester = biographyFocus,
                    onDownToContent = movePastBiography,
                    onUpToBack = {
                        scrollScope.launch {
                            listState.scrollToItem(0)
                            withFrameNanos { }
                            backFocus.requestFocus()
                        }
                    }
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
                item(key = "known-for-row") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(personInfo!!.knownForTitles, key = { "${it.title.type}:${it.title.id}" }) { credit ->
                            PersonKnownForCard(credit,
                                modifier = if (credit == personInfo!!.knownForTitles.first()) Modifier.focusRequester(contentFocus) else Modifier,
                                onClick = { onOpenTitle(credit.title) })
                        }
                    }
                }
            }

            if (!personInfo?.filmography.isNullOrEmpty()) {
                val allCredits = personInfo!!.filmography
                val pageCount = (allCredits.size + 49) / 50
                val page = filmographyPage.coerceIn(0, pageCount - 1)
                val start = page * 50
                val credits = allCredits.drop(start).take(50)
                // LazyColumn items before the filmography heading (loaded state).
                val filmographyHeaderIndex = 3 +
                    (if (error != null) 1 else 0) +
                    (if (!personInfo?.alsoKnownAs.isNullOrEmpty()) 1 else 0) +
                    (if (!personInfo?.knownForTitles.isNullOrEmpty()) 2 else 0)
                val navigatePage: (Int) -> Unit = { target ->
                    val bounded = target.coerceIn(0, pageCount - 1)
                    if (bounded != page) {
                        onFilmographyPageChange(bounded)
                        scrollScope.launch { listState.scrollToItem(filmographyHeaderIndex) }
                    }
                }
                item(key = "filmography-heading") {
                    Text("Filmography  ·  ${start + 1}–${start + credits.size} of ${allCredits.size}",
                        fontSize = 22.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                }
                if (pageCount > 1) {
                    item(key = "filmography-pages-top") {
                        PersonFilmographyPager(page, pageCount, navigatePage)
                    }
                }
                items(credits, key = { "${it.title.type}:${it.title.id}" }) { credit ->
                    PersonFilmographyRow(credit,
                        modifier = if (personInfo!!.knownForTitles.isEmpty() && credit == credits.first()) Modifier.focusRequester(contentFocus) else Modifier,
                        onClick = { onOpenTitle(credit.title) })
                }
                if (pageCount > 1) {
                    item(key = "filmography-pages-bottom") {
                        PersonFilmographyPager(page, pageCount, navigatePage)
                    }
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

/** Up/Down navigate past the panel until the viewer explicitly chooses to read. */
@Composable
private fun PersonBiography(text: String, requester: FocusRequester, onUpToBack: () -> Unit,
    onDownToContent: () -> Unit) {
    val scroll = rememberScrollState()
    val step = with(LocalDensity.current) { 72.dp.toPx() }
    var focused by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf(false) }
    fun act(action: JaBiographyNavigation.Action) {
        when (action) {
            JaBiographyNavigation.Action.BEGIN_READING -> reading = true
            JaBiographyNavigation.Action.END_READING -> reading = false
            JaBiographyNavigation.Action.SCROLL_UP -> scroll.dispatchRawDelta(-step)
            JaBiographyNavigation.Action.SCROLL_DOWN -> scroll.dispatchRawDelta(step)
            JaBiographyNavigation.Action.PREVIOUS -> { reading = false; onUpToBack() }
            JaBiographyNavigation.Action.NEXT -> { reading = false; onDownToContent() }
            else -> Unit
        }
    }
    BackHandler(reading) { act(JaBiographyNavigation.action(reading, JaBiographyNavigation.Key.BACK, false)) }
    Column(
        Modifier.fillMaxWidth().focusRequester(requester)
            .onFocusChanged { focused = it.isFocused; if (!focused) reading = false }
            .then(if (focused) Modifier.border(2.dp, if (reading) personAccent else Color.White, RoundedCornerShape(10.dp)) else Modifier)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false
                else when (event.key) {
                    Key.DirectionDown -> { act(JaBiographyNavigation.action(reading, JaBiographyNavigation.Key.DOWN, scroll.canScrollForward)); true }
                    Key.DirectionUp -> { act(JaBiographyNavigation.action(reading, JaBiographyNavigation.Key.UP, scroll.canScrollBackward)); true }
                    else -> false
                }
            }.clickable(role = androidx.compose.ui.semantics.Role.Button) {
                act(JaBiographyNavigation.action(reading, JaBiographyNavigation.Key.SELECT, false))
            }.padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Biography", fontSize = 22.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
        Text(if (reading) "Reading · Up/Down to scroll · Back to finish" else "OK to read · Down to skip",
            color = personAccent, fontSize = 11.sp)
        Column(Modifier.fillMaxWidth().heightIn(max = 220.dp)
            .focusProperties { canFocus = false }.verticalScroll(scroll)) {
            Text(text, color = Color.White, fontSize = 15.sp, lineHeight = 23.sp)
        }
    }
}

@Composable
private fun PersonKnownForCard(credit: TmdbPersonFilmCredit, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val watched by WatchedStore.watched.collectAsState()
    val label = (if (WatchedStore.key(credit.title.id, credit.title.type) in watched) "✓ " else "") + credit.title.title
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier.width(130.dp)
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
        Text(label, color = Color.White, fontSize = 12.sp, maxLines = 2,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 5.dp))
        credit.year?.let { Text(it, color = personMuted, fontSize = 11.sp) }
    }
}

@Composable
private fun PersonFilmographyRow(credit: TmdbPersonFilmCredit, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val watched by WatchedStore.watched.collectAsState()
    val label = (if (WatchedStore.key(credit.title.id, credit.title.type) in watched) "✓ " else "") + credit.title.title
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth()
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
            Text(label, color = Color.White, fontWeight = FontWeight.SemiBold,
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


@Composable
private fun PersonFilmographyPager(page: Int, totalPages: Int, goTo: (Int) -> Unit) {
    val from = (page - 2).coerceAtLeast(0)
    val to = (page + 2).coerceAtMost(totalPages - 1)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        item { Text("Page ${page + 1} of $totalPages", color = personMuted, fontSize = 13.sp) }
        item { PersonPageButton("First", page > 0, false) { goTo(0) } }
        item { PersonPageButton("‹ Prev", page > 0, false) { goTo(page - 1) } }
        items((from..to).toList(), key = { "page-$it" }) { number ->
            PersonPageButton("${number + 1}", true, number == page) { goTo(number) }
        }
        item { PersonPageButton("Next ›", page < totalPages - 1, false) { goTo(page + 1) } }
        item { PersonPageButton("Last", page < totalPages - 1, false) { goTo(totalPages - 1) } }
    }
}

@Composable
private fun PersonPageButton(
    text: String, enabled: Boolean, selected: Boolean, onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Button(
        onClick = onClick, enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) personAccent else personSurface,
            contentColor = if (selected) Color.Black else Color.White
        ),
        border = jaButtonFocusBorder(focused),
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 11.dp, vertical = 8.dp),
        modifier = Modifier.onFocusChanged { focused = it.isFocused }
    ) { Text(text, fontSize = 12.sp) }
}
