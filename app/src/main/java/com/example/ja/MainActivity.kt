package com.example.ja

import android.os.Bundle
import org.mozilla.geckoview.GeckoRuntime
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.app.Activity
import android.app.Dialog
import android.widget.Button
import android.widget.LinearLayout
import android.os.Message
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.room.Room
import coil.compose.AsyncImage
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private val bg = Color(0xFF12171D)
private val accent = Color(0xFFE5A43B)
private data class DetailReturnPoint(val title: TitleItem?, val person: TmdbPersonCredit?)
/** Prewarm the SAME GeckoRuntime used by the embedded player.
 * The Firefox add-on persists across restarts; Gecko only downloads it once.
 * Never hold up the catalog UI or title selection while it initializes.
 */
private fun prewarmPlayerAdBlocker(context: Context, mode: String) {
    try {
        val runtime = GeckoRuntime.getDefault(context.applicationContext)
        JaGeckoAdBlockers.prepare(runtime, mode) { status ->
            Log.i("JA_ADBLOCK", "APP_PREWARM mode=$mode status=$status")
        }
    } catch (error: Exception) {
        Log.w("JA_ADBLOCK", "APP_PREWARM_FAILED mode=$mode", error)
        // The GeckoView player can still start with its native popup safeguards.
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "ja.db").build()
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = accent, background = bg, surface = Color(0xFF202832), onBackground = Color.White, onSurface = Color.White, onSurfaceVariant = Color.LightGray)) { StreamingApp(db.dao()) } }
        // Start extension setup as soon as JA opens, rather than waiting for the
        // first Play press. Posting lets Compose draw the initial screen first.
        window.decorView.post {
            val mode = PlayerControlSettings(applicationContext).adBlockerMode
            prewarmPlayerAdBlocker(applicationContext, mode)
            // One-time, read-only device capability report. Does NOT force a
            // decoder, change the TV display mode, or run during playback.
            val app = applicationContext
            @Suppress("DEPRECATION")
            val output = windowManager.defaultDisplay
            Thread({ JaTvMediaDiagnostics.logIfTelevision(app, output) }, "JA-TV-Codec-Info").start()
        }
    }
}

@Composable fun StreamingApp(dao: WatchDao) {
    val appContext = LocalContext.current.applicationContext
    val repo = remember { TmdbRepository() }
    val detailsRepo = remember { TmdbDetailsRepository() }
    val peopleRepo = remember { TmdbPersonRepository() }
    val searchRepo = remember { TmdbSearchRepository() }
    val provider = remember { PlaybackRegistry() }
    val serverDomainLedger = remember { ServerDomainLedger(appContext) }
    val serverPreflight = remember { EmbeddedServerPreflight(provider, serverDomainLedger) }
    val serverPlaybackHistory = remember { ServerPlaybackHistory(appContext) }
    val backgroundVerifier = remember { BackgroundPlaybackVerifier(appContext) }
    val scope = rememberCoroutineScope()
    val settings = remember { PlayerControlSettings(context = appContext) }
    // Persist the order in which titles were favorited separately from the
    // watch-history timestamp, which changes whenever an episode is played.
    val favoritesOrder = remember { FavoritesDisplayOrder(appContext) }
    var showBack by remember { mutableStateOf(settings.showBack) }
    var showRestore by remember { mutableStateOf(settings.showRestore) }
    // Display preferences do not disable server preflight or diagnostic logging.
    var showServerChecks by remember { mutableStateOf(settings.showServerChecks) }
    var showPlayerDiagnostics by remember { mutableStateOf(settings.showPlayerDiagnostics) }
    // Optional experimental TV-only display mode preference. Default OFF.
    var matchTvFrameRate by remember { mutableStateOf(settings.matchTvFrameRate) }
    var adBlockerMode by remember { mutableStateOf(settings.adBlockerMode) }
    var tab by remember { mutableStateOf("Movies") }
    // Keep independent vertical and horizontal scroll positions for Movies and TV.
    val sectionScrollStates = remember { mutableMapOf<String, LazyListState>() }
    val genreScrollStates = remember { mutableMapOf<String, LazyListState>() }
    var genreRows by remember { mutableStateOf(emptyList<GenreRow>()) }
    var genresLoading by remember { mutableStateOf(false) }
    // Movies can switch between the existing genre rows and a paged popular grid.
    var moviesView by remember { mutableStateOf("Popular") }
    val popularGridState = rememberLazyGridState()
    var popularTitles by remember { mutableStateOf(emptyList<TitleItem>()) }
    var popularPage by remember { mutableIntStateOf(0) }
    var popularTotalPages by remember { mutableIntStateOf(1) }
    var popularLoading by remember { mutableStateOf(false) }
    var popularError by remember { mutableStateOf<String?>(null) }
    // TV has its own Popular/Genres toggle, paged results and scroll state.
    // Keep the original TV genre rows and all Movies/Search state intact.
    var tvView by remember { mutableStateOf("Popular") }
    val tvPopularGridState = rememberLazyGridState()
    var tvPopularTitles by remember { mutableStateOf(emptyList<TitleItem>()) }
    var tvPopularPage by remember { mutableIntStateOf(0) }
    var tvPopularTotalPages by remember { mutableIntStateOf(1) }
    var tvPopularLoading by remember { mutableStateOf(false) }
    var tvPopularError by remember { mutableStateOf<String?>(null) }
    // Favorites are one existing database collection, presented by media type.
    // Remember each subsection's scroll offset while browsing title details.
    var favoritesView by remember { mutableStateOf("Movies") }
    val favoriteMovieGridState = rememberLazyGridState()
    val favoriteTvGridState = rememberLazyGridState()
    var movieFavoriteIds by remember { mutableStateOf(emptyList<Int>()) }
    var tvFavoriteIds by remember { mutableStateOf(emptyList<Int>()) }
    var results by remember { mutableStateOf(emptyList<TitleItem>()) }
    var selected by remember { mutableStateOf<TitleItem?>(null) }
    var selectedPerson by remember { mutableStateOf<TmdbPersonCredit?>(null) }
    val detailsBackStack = remember { mutableStateListOf<DetailReturnPoint>() }
    var playback by remember { mutableStateOf<PlaybackSource?>(null) }
    var request by remember { mutableStateOf<PlaybackRequest?>(null) }
    // Ordered media candidates for the current title; never switch on a mere delay.
    var playbackChoices by remember { mutableStateOf(emptyList<PlaybackChoice>()) }
    var playbackChoiceIndex by remember { mutableIntStateOf(0) }
    var history by remember { mutableStateOf(emptyList<WatchItem>()) }
    // Search state lives above the details routes so Back restores the query and results.
    var search by remember { mutableStateOf("") }
    var searchMode by remember { mutableStateOf(JaSearchMode.ALL) }
    val searchGridState = rememberLazyGridState() // Keep search position while viewing title/person details.
    var searchReturnBookmark by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var pendingSearchRestore by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var genreDirectoryBookmark by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    // Keep scroll positions and filmography pages independent for each TMDB person/title.
    val personScrollBookmarks = remember { mutableMapOf<Int, Pair<Int, Int>>() }
    val personFilmographyPages = remember { mutableStateMapOf<Int, Int>() }
    val titleScrollBookmarks = remember { mutableMapOf<String, Int>() }
    var searchResponse by remember { mutableStateOf(JaSearchResponse()) }
    var searchLoading by remember { mutableStateOf(false) }
    var searchHasSearched by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var searchRevision by remember { mutableIntStateOf(0) }
    var searchGenre by remember { mutableStateOf<JaSearchGenre?>(null) }
    var searchGenreTitles by remember { mutableStateOf(emptyList<TitleItem>()) }
    var searchGenreLoading by remember { mutableStateOf(false) }
    var searchGenrePage by remember { mutableIntStateOf(0) }
    var searchGenreTotalPages by remember { mutableIntStateOf(1) }
    var searchGenreLoadingMore by remember { mutableStateOf(false) }
    var searchGenrePageError by remember { mutableStateOf<String?>(null) }
    var genreSearchRevision by remember { mutableIntStateOf(0) }
    var seasons by remember { mutableStateOf(emptyList<Int>()) }
    var season by remember { mutableIntStateOf(1) }
    var episodes by remember { mutableStateOf(emptyList<EpisodeItem>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var playbackLoading by remember { mutableStateOf(false) }
    var serverScanStatus by remember { mutableStateOf<String?>(null) }
    var serverScanKey by remember { mutableStateOf("") }
    // Network checks start on title open. Play reuses the same in-flight scan
    // when possible and never treats HTTP reachability as confirmed video.
    fun preflightTitle(item: TitleItem, s: Int = 0, e: Int = 0) {
        val previousProbes = backgroundVerifier.stop()
        val mediaRequest = PlaybackRequest(item, s, e)
        val key = "${item.type}:${item.id}:$s:$e"
        serverScanKey = key
        serverScanStatus = "Checking available embed servers…"
        val check = serverPreflight.start(scope, mediaRequest)
        scope.launch {
            try {
                // A prior title/season may have opened many hidden sessions.
                // Drain their cancellation before opening this title's probes.
                previousProbes?.join()
                val result = check.await()
                if (serverScanKey == key && selected?.id == item.id && selected?.type == item.type) {
                    // Bounded detached GeckoSessions check provider media while
                    // this detail/season is open. No screen or popup is shown.
                    val ordered = serverPlaybackHistory.rank(mediaRequest, result.ranked, result.responseCodes)
                    if (backgroundVerifier.tvSafeMode) {
                        // HTTP preflight still runs in parallel. Hidden GeckoSessions
                        // are suspended on Android TV after the grey-screen regression.
                        // Preserve all saved per-season/film foreground preferences.
                        serverScanStatus = "Server check: ${result.responding}/${result.checked} pages responded. " +
                            "TV-safe mode: hidden video probes skipped. " +
                            "A working server is confirmed only by visible playback."
                        Log.i("JA_PERF", "TV_SAFE_DETAILS_HTTP_ONLY type=${item.type} tmdb=${item.id}")
                    } else {
                        serverScanStatus = "Server check: ${result.responding}/${result.checked} pages responded. " +
                            "Checking ${ordered.count { it.source is PlaybackSource.Embed }} embeds in parallel. " +
                            "HTTP checks do not confirm playable video."
                        backgroundVerifier.start(scope, mediaRequest, ordered) { checked, total, observed ->
                            if (serverScanKey == key && selected?.id == item.id && playback == null) {
                                serverScanStatus = "Server check: ${result.responding}/${result.checked} pages responded. " +
                                    "Parallel video signals: $observed ($checked/$total completed). " +
                                    "Foreground playback confirms the preferred server."
                            }
                        }
                    }
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (ex: Exception) {
                if (serverScanKey == key) serverScanStatus = "Server checks unavailable; playback can still be attempted."
                Log.w("JA_SERVERS", "PREFLIGHT_FAILED", ex)
            }
        }
    }
    fun refreshHistory() {
        scope.launch {
            val current = dao.all()
            // Reconcile after each save/delete. Playback updates cannot
            // reorder existing favorites; newly favorited IDs append last.
            movieFavoriteIds = favoritesOrder.reconcile("movie", current)
            tvFavoriteIds = favoritesOrder.reconcile("tv", current)
            history = current
        }
    }
    fun runSearch(text: String, desiredMode: JaSearchMode) {
        searchRevision += 1
        searchReturnBookmark = null
        pendingSearchRestore = null
        val revision = searchRevision
        genreSearchRevision += 1
        searchGenre = null
        searchGenreLoading = false
        searchGenreLoadingMore = false
        searchGenreTitles = emptyList()
        searchGenrePage = 0
        searchGenreTotalPages = 1
        searchGenrePageError = null
        searchError = null
        searchHasSearched = true
        // Clear results from the previous search mode immediately, so switching
        // Actors -> Directors cannot display stale actor cards while verifying credits.
        searchResponse = JaSearchResponse()
        scope.launch { searchGridState.scrollToItem(0) }
        Log.i("JA_SEARCH", "QUERY mode=${desiredMode.label} chars=${text.trim().length}")
        if (text.isBlank() && desiredMode != JaSearchMode.GENRES) {
            searchResponse = JaSearchResponse()
            searchLoading = false
            return
        }
        searchLoading = true
        scope.launch {
            try {
                val found = searchRepo.search(text, desiredMode)
                if (searchRevision == revision) {
                    searchResponse = found
                    Log.i("JA_SEARCH", "RESULT mode=${desiredMode.label} titles=${found.titles.size} people=${found.people.size} genres=${found.genres.size}")
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (ex: Exception) {
                if (searchRevision == revision) {
                    searchResponse = JaSearchResponse()
                    searchError = ex.message ?: "Search failed"
                    Log.e("JA_SEARCH", "QUERY_FAILED mode=${desiredMode.label}", ex)
                }
            } finally {
                if (searchRevision == revision) searchLoading = false
            }
        }
    }
    fun browseGenre(genre: JaSearchGenre) {
        genreSearchRevision += 1
        val revision = genreSearchRevision
        genreDirectoryBookmark = searchGridState.firstVisibleItemIndex to searchGridState.firstVisibleItemScrollOffset
        searchGenre = genre
        scope.launch { searchGridState.scrollToItem(0) }
        Log.i("JA_SEARCH", "GENRE_OPEN id=${genre.id} type=${genre.type} name=${genre.name}")
        searchGenreTitles = emptyList()
        searchGenrePage = 0
        searchGenreTotalPages = 1
        searchGenreLoadingMore = false
        searchGenrePageError = null
        searchError = null
        searchGenreLoading = true
        scope.launch {
            try {
                val found = searchRepo.genreTitlesPage(genre, 1)
                if (genreSearchRevision == revision) {
                    searchGenreTitles = found.titles
                    searchGenrePage = found.page
                    searchGenreTotalPages = found.totalPages
                    Log.i("JA_SEARCH", "GENRE_PAGE id=${genre.id} page=${found.page} count=${found.titles.size}")
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (ex: Exception) { if (genreSearchRevision == revision) searchError = ex.message }
            finally { if (genreSearchRevision == revision) searchGenreLoading = false }
        }
    }
    fun loadMoreGenre() {
        val genre = searchGenre ?: return
        if (searchGenreLoading || searchGenreLoadingMore || searchGenrePage == 0 ||
            searchGenrePage >= searchGenreTotalPages || searchGenrePage >= 500) return
        val revision = genreSearchRevision
        val nextPage = searchGenrePage + 1
        searchGenreLoadingMore = true
        searchGenrePageError = null
        scope.launch {
            try {
                val found = searchRepo.genreTitlesPage(genre, nextPage)
                if (genreSearchRevision == revision && searchGenre == genre) {
                    searchGenreTitles = (searchGenreTitles + found.titles).distinctBy { it.type to it.id }
                    searchGenrePage = found.page
                    searchGenreTotalPages = if (found.titles.isEmpty()) found.page else found.totalPages
                    Log.i("JA_SEARCH", "GENRE_MORE id=${genre.id} page=${found.page} total=${searchGenreTitles.size}")
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (ex: Exception) {
                if (genreSearchRevision == revision) searchGenrePageError = "Couldn't load more titles: ${ex.message}"
            } finally {
                if (genreSearchRevision == revision) searchGenreLoadingMore = false
            }
        }
    }
    fun exitGenre() {
        genreSearchRevision++
        searchGenre = null
        searchGenreLoading = false
        searchGenreLoadingMore = false
        pendingSearchRestore = genreDirectoryBookmark
    }
    fun loadMorePopular() {
        if (popularLoading || popularPage >= popularTotalPages || popularPage >= 500) return
        val nextPage = popularPage + 1
        popularLoading = true
        popularError = null
        scope.launch {
            try {
                val found = searchRepo.popularMoviesPage(nextPage)
                popularTitles = (popularTitles + found.titles).distinctBy { it.type to it.id }
                popularPage = found.page
                popularTotalPages = if (found.titles.isEmpty()) found.page else found.totalPages
                Log.i("JA_MOVIES", "POPULAR_PAGE page=${found.page} total=${popularTitles.size}")
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (ex: Exception) {
                popularError = "Couldn't load popular movies: ${ex.message}"
                Log.w("JA_MOVIES", "POPULAR_LOAD_FAILED page=$nextPage", ex)
            } finally { popularLoading = false }
        }
    }
    fun loadMorePopularTV() {
        if (tvPopularLoading || tvPopularPage >= tvPopularTotalPages || tvPopularPage >= 500) return
        val nextPage = tvPopularPage + 1
        tvPopularLoading = true
        tvPopularError = null
        scope.launch {
            try {
                val found = searchRepo.popularShowsPage(nextPage)
                tvPopularTitles = (tvPopularTitles + found.titles).distinctBy { it.type to it.id }
                tvPopularPage = found.page
                tvPopularTotalPages = if (found.titles.isEmpty()) found.page else found.totalPages
                Log.i("JA_TV", "POPULAR_PAGE page=${found.page} total=${tvPopularTitles.size}")
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (ex: Exception) {
                tvPopularError = "Couldn't load popular TV shows: ${ex.message}"
                Log.w("JA_TV", "POPULAR_LOAD_FAILED page=$nextPage", ex)
            } finally { tvPopularLoading = false }
        }
    }
    LaunchedEffect(tab, if (tab == "Movies") moviesView else tvView) {
        when (tab) {
            "Movies" -> if (moviesView == "Popular" && popularPage == 0 && !popularLoading) {
                loadMorePopular()
            }
            "TV" -> if (tvView == "Popular" && tvPopularPage == 0 && !tvPopularLoading) {
                loadMorePopularTV()
            }
        }
    }
    // Fetch the existing genre rows only when the corresponding Genres view is selected.
    // Popular switching keeps the previously fetched pages and individual grid scroll positions.
    LaunchedEffect(tab, if (tab == "Movies") moviesView else tvView) {
        refreshHistory()
        when (tab) {
            "Movies", "TV" -> {
                val activeView = if (tab == "Movies") moviesView else tvView
                if (activeView == "Genres") {
                    results = emptyList()
                    genreRows = emptyList()
                    genresLoading = true
                    error = null
                    try { genreRows = repo.genreRows(if (tab == "Movies") "movie" else "tv") }
                    catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                    catch (e: Exception) { error = e.message }
                    finally { genresLoading = false }
                }
            }
            "Search" -> { error = null }
            // Favorite posters derive directly from the current history state;
            // a freshly added/removed favorite should never show stale results.
            "Favorites" -> Unit
            "Continue" -> {
                results = history.filter { it.positionMs > 0 }.distinctBy { it.mediaId to it.mediaType }.map { TitleItem(it.mediaId, it.title, "", it.poster, null, it.mediaType) }
            }
        }
    }
    // DETAILS_NAV v1.1.4b: retain every title/person route so Back returns to its caller.
    LaunchedEffect(Unit) { Log.i("JA_DETAILS_NAV", "v1.1.4b navigation patch active") }
    fun showTitle(item: TitleItem) {
        selected = item; episodes = emptyList(); seasons = emptyList()
        // TV starts with a provisional first-episode probe. When TMDB returns
        // the actual season/episode, that request gets its own precise scan.
        preflightTitle(item, if (item.type == "tv") 1 else 0, if (item.type == "tv") 1 else 0)
        if (item.type == "tv") scope.launch {
            try {
                val loadedSeasons = repo.seasons(item.id)
                val selectedSeason = loadedSeasons.firstOrNull() ?: 1
                val loadedEpisodes = repo.episodes(item.id, selectedSeason)
                // Ignore an old response if the user has already navigated away.
                if (selected?.id == item.id && selected?.type == item.type) {
                    seasons = loadedSeasons; season = selectedSeason; episodes = loadedEpisodes
                    preflightTitle(item, selectedSeason, loadedEpisodes.firstOrNull()?.episode ?: 1)
                }
            } catch (e: Exception) { if (selected?.id == item.id) error = e.message }
        }
    }
    fun open(item: TitleItem, fromDetails: Boolean = false) {
        if (!fromDetails && tab == "Search" && selected == null && selectedPerson == null) {
            searchReturnBookmark = searchGridState.firstVisibleItemIndex to searchGridState.firstVisibleItemScrollOffset
        }
        val previousTitle = selected
        if (fromDetails && (previousTitle != null || selectedPerson != null)) {
            detailsBackStack.add(DetailReturnPoint(previousTitle, selectedPerson))
        } else if (!fromDetails) {
            detailsBackStack.clear()
        }
        Log.i("JA_DETAILS_NAV", "OPEN_TITLE id=${item.id} type=${item.type} viaDetails=$fromDetails depth=${detailsBackStack.size}")
        selectedPerson = null
        showTitle(item)
    }
    fun openPerson(person: TmdbPersonCredit) {
        if (tab == "Search" && selected == null && selectedPerson == null) {
            searchReturnBookmark = searchGridState.firstVisibleItemIndex to searchGridState.firstVisibleItemScrollOffset
        }
        // From title details, remember the title. From Search, return straight to Search.
        if (selected != null || selectedPerson != null) {
            detailsBackStack.add(DetailReturnPoint(selected, selectedPerson))
        } else {
            detailsBackStack.clear()
        }
        selectedPerson = person
        Log.i("JA_DETAILS_NAV", "OPEN_PERSON id=${person.id} name=${person.name} depth=${detailsBackStack.size}")
    }
    fun backFromDetails() {
        backgroundVerifier.stop()
        if (detailsBackStack.isNotEmpty()) {
            val previous = detailsBackStack.removeAt(detailsBackStack.lastIndex)
            selectedPerson = previous.person
            if (previous.title == null) {
                selected = null
                episodes = emptyList()
                seasons = emptyList()
            } else if (selected?.id != previous.title.id || selected?.type != previous.title.type) {
                showTitle(previous.title)
            }
            Log.i("JA_DETAILS_NAV", "BACK_TO_${if (previous.person == null) "TITLE" else "PERSON"} id=${previous.title?.id ?: 0} depth=${detailsBackStack.size}")
        } else {
            selectedPerson = null
            selected = null
            Log.i("JA_DETAILS_NAV", "BACK_TO_CATALOG depth=0")
        }
        if (tab == "Search" && selected == null && selectedPerson == null) {
            pendingSearchRestore = searchReturnBookmark
        }
    }
    fun play(item: TitleItem, s: Int = 0, e: Int = 0) {
        // Offscreen verification must not compete with real foreground playback.
        // Keep the cancelled Job and await GeckoSession cleanup on the UI
        // coroutine before starting the visible player.
        val closingProbeScan = backgroundVerifier.stop()
        error = null
        playback = null
        playbackChoices = emptyList()
        playbackChoiceIndex = 0
        playbackLoading = true
        Log.d("JA_PLAYBACK", "Play clicked: ${item.title} (${item.type}, TMDB ${item.id})")
        scope.launch {
            try {
                // Wait until ALL detached GeckoSessions have been closed.
                // This is especially important on Android TV hardware: up to
                // 15 background video decoders can otherwise contend with the
                // single foreground video during the startup transition.
                closingProbeScan?.join()
                Log.i("JA_PERF", "FOREGROUND_PROBES_DRAINED type=${item.type} tmdb=${item.id}")
                // VidSrc-style TMDB embed URLs use the existing numeric TMDB ID.
                val r = PlaybackRequest(item, s, e)
                request = r
                // If the user presses Play during a scan, reuse the in-flight
                // probes and allow them a bounded period to finish. If slow or
                // offline, always fall back to JA's original known providers.
                val checked = withTimeoutOrNull(8600) { serverPreflight.start(scope, r).await() }
                val candidates = serverDomainLedger.apply(
                    checked?.ranked?.takeIf { it.isNotEmpty() } ?: provider.choices(r)
                )
                val knownRanked = serverPlaybackHistory.rank(r, candidates, checked?.responseCodes.orEmpty())
                // A real foreground success always wins over a tentative
                // detached-session signal; a new season can use its probe.
                val choices = if (serverPlaybackHistory.hasForegroundPreference(r)) knownRanked
                    else backgroundVerifier.prioritize(r, knownRanked)
                if (choices.isEmpty()) {
                    error = "No configured playback source for this TMDB title."
                    return@launch
                }
                backgroundVerifier.stop()
                playbackChoices = choices
                playbackChoiceIndex = 0
                playback = choices.first().source
                Log.i("JA_SOURCES", "SOURCE_START id=${choices.first().id} count=${choices.size} " +
                    "type=${item.type} tmdb=${item.id}")
                val old = dao.find(item.id, item.type, s, e)
                dao.save(WatchItem(item.id, item.type, s, e, item.title, item.poster, old?.favorite ?: false, old?.positionMs ?: 0))
                refreshHistory()
            } catch (ex: Exception) {
                error = "Playback lookup failed: ${ex.message ?: "Unknown error"}"
                Log.e("JA_PLAYBACK", "Playback lookup failed", ex)
            } finally {
                playbackLoading = false
            }
        }
    }
    fun nextPlaybackSource(reason: String, recordFailure: Boolean = true) {
        val next = playbackChoiceIndex + 1
        val failed = playbackChoices.getOrNull(playbackChoiceIndex)
        // Explicit errors cool down the failing server for this title.
        // A manual Next Server request is a preference, not evidence of failure.
        if (recordFailure && failed != null && request != null) {
            serverPlaybackHistory.recordExplicitFailure(request!!, failed.id, reason)
            backgroundVerifier.invalidate(request!!, failed.id)
        }
        if (next >= playbackChoices.size) {
            // All configured providers have failed; never display a false recovery banner.
            Log.w("JA_SOURCES", "SOURCES_EXHAUSTED reason=$reason")
            return
        }
        Log.w("JA_SOURCES", "SOURCE_FALLBACK from=${playbackChoices[playbackChoiceIndex].id} " +
            "to=${playbackChoices[next].id} reason=$reason")
        playbackChoiceIndex = next
        playback = playbackChoices[next].source
    }
    BackHandler(playback != null || selected != null || selectedPerson != null || (tab == "Search" && searchGenre != null)) {
        when {
            playback != null -> playback = null
            selected != null || selectedPerson != null -> backFromDetails()
            searchGenre != null -> exitGenre()
        }
    }
    // Playback occupies the entire activity instead of the catalog's padded column.
    if (playback != null) {
        PlayerScreen(
            source = playback!!, request = request, dao = dao,
            showBack = showBack, showRestore = showRestore,
            showDiagnostics = showPlayerDiagnostics,
            matchTvFrameRate = matchTvFrameRate,
            adBlockerMode = adBlockerMode,
            currentSourceLabel = playbackChoices.getOrNull(playbackChoiceIndex)?.label ?: "Source",
            hasNextSource = playbackChoiceIndex < playbackChoices.lastIndex,
            onTryNextSource = { nextPlaybackSource("MANUAL_NEXT_SERVER", recordFailure = false) },
            onSourceStarted = { startedSource ->
                val current = playbackChoices.getOrNull(playbackChoiceIndex)
                val activeRequest = request
                if (current?.source == startedSource && activeRequest != null) {
                    // Alias hosts are only promoted after foreground video
                    // activity, never just an HTTP 301 or a headless probe.
                    val canonicalId = serverDomainLedger.onMediaStarted(
                        current, provider.choices(activeRequest)
                    )
                    serverPlaybackHistory.recordVideoStarted(activeRequest, canonicalId)
                    Log.i("JA_SERVERS", "VIDEO_STARTED_RECORDED provider=$canonicalId " +
                        "series=${activeRequest.media.type == "tv"} tmdb=${activeRequest.media.id} " +
                        "season=${activeRequest.season} episode=${activeRequest.episode}")
                }
            },
            onTopLevelRedirect = { target ->
                val active = playbackChoices.getOrNull(playbackChoiceIndex)
                val playingRequest = request
                if (active != null && playingRequest != null) {
                    serverDomainLedger.observeNavigationRedirect(
                        active, target, provider.choices(playingRequest)
                    )
                }
            },
            onSourceFailure = { failedSource, reason ->
                // Ignore delayed reports emitted after a different player opened.
                if (playback == failedSource) nextPlaybackSource(reason)
            },
            onClose = { playback = null }
        )
        return
    }
    Column(Modifier.fillMaxSize().background(bg).padding(16.dp)) {
        Text("JA", style = MaterialTheme.typography.headlineMedium, color = accent)
        Spacer(Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("Movies", "TV", "Search", "Favorites", "Continue", "Settings")) { t ->
                TvFocusButton(onClick = { tab = t; selected = null; selectedPerson = null; detailsBackStack.clear(); playback = null }, colors = ButtonDefaults.buttonColors(containerColor = if (tab == t) accent else Color.DarkGray)) { Text(t, color = Color.Black) }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (playbackLoading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Looking up playback source…") }
        error?.let { Text(it, color = Color(0xFFFFA8A8)) }
        if (tab == "Settings" && selected == null) {
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                Text("Player controls", style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text("Choose which controls appear over GeckoView. Android's system Back action still exits playback.", color = Color.LightGray)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = showBack, onCheckedChange = { showBack = it; settings.showBack = it })
                    Text("Show Back button", color = Color.White)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = showRestore, onCheckedChange = { showRestore = it; settings.showRestore = it })
                    Text("Show Restore Episode button", color = Color.White)
                }
                Spacer(Modifier.height(16.dp))
                Text("Diagnostics and server checks", style = MaterialTheme.typography.titleLarge, color = Color.White)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = showServerChecks,
                        onCheckedChange = { showServerChecks = it; settings.showServerChecks = it }
                    )
                    Text("Show server check messages in title details", color = Color.White)
                }
                Text(
                    "Server checks still run automatically when this message is hidden.",
                    color = Color.LightGray,
                    style = MaterialTheme.typography.bodySmall
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = showPlayerDiagnostics,
                        onCheckedChange = { showPlayerDiagnostics = it; settings.showPlayerDiagnostics = it }
                    )
                    Text("Show player diagnostics overlay", color = Color.White)
                }
                Text(
                    "Shows provider, loading, media and navigation diagnostics over GeckoView. " +
                        "The normal player controls are unchanged.",
                    color = Color.LightGray,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(16.dp))
                Text("Android TV display performance", style = MaterialTheme.typography.titleLarge, color = Color.White)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = matchTvFrameRate,
                        onCheckedChange = { enabled ->
                            matchTvFrameRate = enabled
                            settings.matchTvFrameRate = enabled
                        }
                    )
                    Text("Match TV frame rate during playback (experimental)", color = Color.White)
                }
                Text(
                    "On Android TV, JA uses verified video frame statistics to request a matching display mode. " +
                        "Your TV must allow non-seamless changes. The screen may briefly go black. " +
                        "The original display preference is restored when playback closes. Off by default.",
                    color = Color.LightGray, style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(16.dp))
                Text("Embedded player ad blocking", style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text(
                    "Choose ONE Firefox ad blocker. GeckoView initializes it when JA opens, and downloads the Mozilla-signed add-on only if needed. " +
                        "Switch if a server does not load. Popup windows and JS prompts are blocked even when Off is selected.",
                    color = Color.LightGray, style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        JaGeckoAdBlockers.UBLOCK to "uBlock Origin",
                        JaGeckoAdBlockers.ADGUARD to "AdGuard",
                        JaGeckoAdBlockers.OFF to "Off"
                    ).forEach { (mode, name) ->
                        TvFocusButton(
                            onClick = {
                                adBlockerMode = mode
                                settings.adBlockerMode = mode
                                // Apply the new filter choice while in Settings.
                                prewarmPlayerAdBlocker(appContext, mode)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (adBlockerMode == mode) accent else Color.DarkGray
                            )
                        ) { Text(name, color = Color.Black) }
                    }
                }
                Text("Selection applies in the background; the next player uses the selected blocker.",
                    color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                Text("Settings are saved on this device.", color = Color.LightGray)
            }
        } else if (selectedPerson != null) {
            val activePerson = selectedPerson!!
            key("person:${activePerson.id}") {
            TmdbPersonScreen(
                person = activePerson,
                repository = peopleRepo,
                onBack = { backFromDetails() },
                backLabel = if (selected == null) "Search" else "title",
                onOpenTitle = { creditTitle -> open(creditTitle, fromDetails = true) },
                filmographyPage = personFilmographyPages[activePerson.id] ?: 0,
                onFilmographyPageChange = { page -> personFilmographyPages[activePerson.id] = page },
                restoreScrollPosition = personScrollBookmarks[activePerson.id],
                onSaveScrollPosition = { index, offset ->
                    personScrollBookmarks[activePerson.id] = index to offset
                }
            )
            }
        } else if (selected != null) {
            val item = selected!!
            if (showServerChecks) {
                serverScanStatus?.let { status ->
                    Text(status, color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                }
            }
            key("title:${item.type}:${item.id}") {
            TitleDetailsScreen(
                item = item,
                repo = detailsRepo,
                isFavorite = history.any { it.mediaId == item.id && it.mediaType == item.type && it.favorite },
                onPlay = {
                    if (item.type == "movie") play(item)
                    else play(item, season, episodes.firstOrNull()?.episode ?: 1)
                },
                onToggleFavorite = {
                    scope.launch {
                        val old = dao.find(item.id, item.type, 0, 0)
                        dao.save(WatchItem(item.id, item.type, title = item.title, poster = item.poster,
                            favorite = !(old?.favorite ?: false), positionMs = old?.positionMs ?: 0))
                        refreshHistory()
                    }
                },
                onOpenRecommendation = { suggested -> open(suggested, fromDetails = true) },
                onOpenPerson = { person -> openPerson(person) },
                onBack = { backFromDetails() },
                backLabel = detailsBackStack.lastOrNull()?.let { destination ->
                    destination.person?.name ?: destination.title?.title ?: "Search"
                } ?: if (tab == "Search") "Search" else "Catalog",
                seasons = seasons,
                season = season,
                episodes = episodes,
                onSeasonSelected = { chosen ->
                    season = chosen
                    episodes = emptyList()
                    scope.launch {
                        try {
                            episodes = repo.episodes(item.id, chosen)
                            if (selected?.id == item.id) {
                                preflightTitle(item, chosen, episodes.firstOrNull()?.episode ?: 1)
                            }
                        } catch (ex: Exception) { error = ex.message }
                    }
                },
                onEpisodePlay = { ep -> play(item, ep.season, ep.episode) },
                restoreScrollY = titleScrollBookmarks["${item.type}:${item.id}"],
                onSaveScrollY = { offset -> titleScrollBookmarks["${item.type}:${item.id}"] = offset }
            )
            }
        } else {
            if (tab == "Search") {
                JaSearchScreen(
                    query = search, onQueryChange = { search = it },
                    gridState = searchGridState,
                    mode = searchMode,
                    onModeChange = { newMode ->
                        searchMode = newMode
                        runSearch(search, newMode)
                    },
                    onSearch = { runSearch(search, searchMode) },
                    loading = searchLoading,
                    hasSearched = searchHasSearched,
                    error = searchError,
                    results = searchResponse,
                    selectedGenre = searchGenre,
                    genreTitles = searchGenreTitles,
                    genreLoading = searchGenreLoading,
                    genreLoadingMore = searchGenreLoadingMore,
                    genreHasMore = searchGenrePage in 1 until searchGenreTotalPages,
                    genrePageError = searchGenrePageError,
                    onLoadMoreGenre = { loadMoreGenre() },
                    onOpenGenre = { browseGenre(it) },
                    onCloseGenre = { exitGenre() },
                    onOpenTitle = { open(it) },
                    onOpenPerson = { openPerson(it) },
                    restorePosition = pendingSearchRestore,
                    onPositionRestored = { pendingSearchRestore = null }
                )
            } else if (tab == "Movies" || tab == "TV") {
                val isMovieTab = tab == "Movies"
                val activeView = if (isMovieTab) moviesView else tvView
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Popular", "Genres").forEach { view ->
                        TvSecondaryButton(
                            text = view,
                            selected = activeView == view,
                            onClick = { if (isMovieTab) moviesView = view else tvView = view }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (activeView == "Popular") {
                    // The TV and Movies Popular lists share layout only, not data or scroll state.
                    val titles = if (isMovieTab) popularTitles else tvPopularTitles
                    val gridState = if (isMovieTab) popularGridState else tvPopularGridState
                    val loading = if (isMovieTab) popularLoading else tvPopularLoading
                    val loadedPage = if (isMovieTab) popularPage else tvPopularPage
                    val totalPages = if (isMovieTab) popularTotalPages else tvPopularTotalPages
                    val pageError = if (isMovieTab) popularError else tvPopularError
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 152.dp),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "popular-title") {
                            Column {
                                Text(if (isMovieTab) "Popular Movies" else "Popular TV Shows",
                                    style = MaterialTheme.typography.titleLarge, color = Color.White)
                                Text("${titles.size} titles loaded", color = Color.LightGray)
                            }
                        }
                        gridItems(titles, key = { "popular-${it.type}-${it.id}" }) { item ->
                            JaSearchTitleCard(item) { open(item) }
                        }
                        if (pageError != null) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "popular-error") {
                                Text(pageError ?: "", color = Color(0xFFFFA8A8))
                            }
                        }
                        if (loading) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "popular-loading") {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                        }
                        if (!loading && (loadedPage == 0 || loadedPage < totalPages)) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "popular-more") {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                                    TvFocusButton(
                                        onClick = { if (isMovieTab) loadMorePopular() else loadMorePopularTV() },
                                        colors = ButtonDefaults.buttonColors(containerColor = accent)
                                    ) { Text(if (loadedPage == 0) "Retry" else "Load More", color = Color.Black) }
                                }
                            }
                        }
                    }
                } else {
                    if (genresLoading) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Loading genre rows…", color = Color.LightGray)
                    }
                    val sectionState = sectionScrollStates.getOrPut(tab) { LazyListState() }
                    LazyColumn(state = sectionState, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        items(genreRows, key = { it.name }) { row ->
                            Column {
                                Text(row.name, style = MaterialTheme.typography.titleLarge, color = Color.White)
                                Spacer(Modifier.height(8.dp))
                                val rowState = genreScrollStates.getOrPut("$tab:${row.name}") { LazyListState() }
                                LazyRow(state = rowState, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    items(row.titles, key = { "${it.type}-${it.id}" }) { item ->
                                        PosterTile(item) { open(item) }
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (tab == "Favorites") {
                LaunchedEffect(favoritesView) {
                    Log.i("JA_FAVORITES", "GRID_V115O_VERTICAL view=$favoritesView")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Movies", "TV").forEach { view ->
                        TvSecondaryButton(
                            text = view,
                            selected = favoritesView == view,
                            onClick = { favoritesView = view }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                // The favorite flag is stored in WatchItem. The display order
                // is independent of WatchItem.updatedAt, which also tracks
                // recently watched titles. New favorites append to the end.
                val favoriteType = if (favoritesView == "Movies") "movie" else "tv"
                val orderedIds = if (favoriteType == "movie") movieFavoriteIds else tvFavoriteIds
                val favoriteById = history.asSequence()
                    .filter { it.favorite && it.mediaType == favoriteType }
                    .distinctBy { it.mediaId }
                    .associateBy { it.mediaId }
                val favoriteTitles = orderedIds.mapNotNull { favoriteById[it] }
                    .map { TitleItem(it.mediaId, it.title, "", it.poster, null, it.mediaType) }
                Text("Favorite $favoritesView", style = MaterialTheme.typography.titleLarge, color = Color.White)
                Spacer(Modifier.height(8.dp))
                if (favoriteTitles.isEmpty()) {
                    Text("No favorite ${if (favoriteType == "movie") "movies" else "TV shows"} yet.", color = Color.LightGray)
                } else {
                    val gridState = if (favoriteType == "movie") favoriteMovieGridState else favoriteTvGridState
                    // Constrain the grid below the two tab buttons. A capped
                    // column count ensures eight favorites form multiple
                    // vertical rows even on a wide Android TV display.
                    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                        val columnCount = when {
                            maxWidth < 340.dp -> 1
                            maxWidth < 640.dp -> 2
                            maxWidth < 960.dp -> 3
                            else -> 4
                        }
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(columnCount),
                            state = gridState,
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(bottom = 24.dp)
                        ) {
                            gridItems(favoriteTitles, key = { "favorite-${it.type}-${it.id}" }) { item ->
                                JaSearchTitleCard(item) { open(item) }
                            }
                        }
                    }
                }
            } else {
                Text(tab, style = MaterialTheme.typography.titleLarge, color = Color.White)
                Spacer(Modifier.height(8.dp))
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    results.chunked(50).forEach { group ->
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 14.dp)) {
                            items(group, key = { "${it.type}-${it.id}" }) { item ->
                                PosterTile(item) { open(item) }
                            }
                        }
                    }
                }
            }
        }
    }
}

// Compact second-level navigation uses a much smaller visual footprint than
// the primary Movies / TV / Search / Favorites top row. Do not shrink the
// primary row or the Load More / Back / player buttons.
@Composable
private fun TvSecondaryButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Button(
        onClick = onClick,
        modifier = Modifier
            .height(26.dp)
            .onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(18.dp)) else Modifier),
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (selected) accent else Color.DarkGray)
    ) {
        Text(text, fontSize = 11.sp, maxLines = 1,
            color = if (selected) Color.Black else Color.White)
    }
}

// The remote focus ring is intentionally white and only visible while focused.
@Composable
private fun TvFocusButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    content: @Composable RowScope.() -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Button(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier.border(3.dp, Color.White, RoundedCornerShape(24.dp)) else Modifier),
        colors = colors,
        content = content
    )
}

@Composable
private fun TvFocusTextButton(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    var focused by remember { mutableStateOf(false) }
    TextButton(
        onClick = onClick,
        modifier = Modifier.onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier.border(3.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier),
        content = content
    )
}

@Composable private fun PosterTile(item: TitleItem, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier.width(140.dp)
            .onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier.border(3.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
            .clickable(onClick = onClick)
            .focusable()
            .padding(3.dp)
    ) {
        AsyncImage(item.posterUrl, item.title, Modifier.fillMaxWidth().height(190.dp), contentScale = ContentScale.Crop)
        Text(item.title, modifier = Modifier.fillMaxWidth(), maxLines = 2,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, color = Color.White)
    }
}

@Composable fun PlayerScreen(
    source: PlaybackSource,
    request: PlaybackRequest?,
    dao: WatchDao,
    showBack: Boolean,
    showRestore: Boolean,
    showDiagnostics: Boolean = false,
    matchTvFrameRate: Boolean = false,
    adBlockerMode: String = JaGeckoAdBlockers.UBLOCK,
    currentSourceLabel: String = "Source",
    hasNextSource: Boolean = false,
    onTryNextSource: () -> Unit = {},
    onSourceStarted: (PlaybackSource) -> Unit = { _ -> },
    onTopLevelRedirect: (String) -> Unit = {},
    onSourceFailure: (PlaybackSource, String) -> Unit = { _, _ -> },
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playbackActivity = context as? Activity
    val frameMatcher = remember(source, playbackActivity, matchTvFrameRate) {
        if (matchTvFrameRate && playbackActivity != null) JaTvFrameRateMatcher(playbackActivity) else null
    }
    DisposableEffect(frameMatcher) {
        onDispose { frameMatcher?.close() }
    }
    var playerError by remember(source) { mutableStateOf<String?>(null) }
    var confirmedPlayback by remember(source) { mutableStateOf(false) }
    // A running media session is evidence that the embed is active, even if
    // the nested video frame never emits VIDEO_STARTED. Do not mistake it for
    // proof that the requested title is available (ads may also play media).
    var geckoMediaActivity by remember(source) { mutableStateOf(false) }
    // No arbitrary playback-confirmation timeout: an iframe can play media
    // without exposing its state to JA. Fallback uses explicit media/page errors.
    var webStatus by remember(source) { mutableStateOf<String?>(null) }
    var resetCount by remember(source) { mutableIntStateOf(0) }
    var videoStatus by remember(source) { mutableStateOf("Video status: not inspected") }
    var lastConsoleError by remember(source) { mutableStateOf<String?>(null) }
    var iframeStatus by remember(source) { mutableStateOf("Frames: not inspected") }
    var fullscreenStatus by remember(source) { mutableStateOf("Fullscreen: not requested") }
    var loadCompleted by remember(source) { mutableStateOf(false) }
    var pendingNavigation by remember(source) { mutableStateOf<String?>(null) }
    var navigationApproval by remember(source) { mutableStateOf<String?>(null) }
    var currentMainHost by remember(source) { mutableStateOf<String?>(null) }
    var navigationNote by remember(source) { mutableStateOf("No external navigation detected") }
    val approvedHosts = remember(source) { mutableStateListOf<String>() }
    var originalReloadCount by remember(source) { mutableIntStateOf(0) }
    var compatibilityMode by remember(source) { mutableStateOf(true) }
    var pageProgress by remember(source) { mutableIntStateOf(0) }
    playerError?.let { Text(it, color = Color(0xFFFFA8A8)) }
    webStatus?.let { Text(it, color = Color.LightGray) }
    when (source) {
        is PlaybackSource.Native -> {
            val player = remember(source.url) { ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(source.url))
                prepare()
                playWhenReady = true
            } }
            DisposableEffect(player) {
                val listener = object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        playerError = "Native player failed: ${error.message ?: "Unknown media error"}"
                        Log.e("JA_PLAYBACK", "Native playback error", error)
                        onSourceFailure(source, "NATIVE_PLAYBACK_ERROR")
                    }
                }
                player.addListener(listener)
                onDispose { player.removeListener(listener) }
            }
            LaunchedEffect(player, request) {
                val r = request ?: return@LaunchedEffect
                val old = dao.find(r.media.id, r.media.type, r.season, r.episode)
                if (old != null && old.positionMs > 0) player.seekTo(old.positionMs)
            }
            DisposableEffect(player, request) {
                onDispose {
                    val r = request
                    if (r != null) scope.launch { val old = dao.find(r.media.id, r.media.type, r.season, r.episode); dao.save(WatchItem(r.media.id, r.media.type, r.season, r.episode, r.media.title, r.media.poster, old?.favorite ?: false, player.currentPosition.coerceAtLeast(0))) }
                    player.release()
                }
            }
            Box(Modifier.fillMaxSize()) {
                AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = true } }, modifier = Modifier.fillMaxSize())
                TvFocusButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart)) { Text("Back") }
            }
        }
        is PlaybackSource.Embed -> {
            // GeckoView is the sole embedded player for website-based sources.
            // No Chrome Custom Tabs, external browser, or Android WebView controls.
            Box(Modifier.fillMaxSize()) {
                GeckoExperimentalPlayer(
                    url = source.url,
                    showRestore = showRestore,
                    showDiagnostics = showDiagnostics,
                    adBlockerMode = adBlockerMode,
                    diagnosticsLabel = currentSourceLabel,
                    onMediaStarted = {
                        if (!confirmedPlayback) onSourceStarted(source)
                        confirmedPlayback = true
                        Log.i("JA_SOURCES", "SOURCE_PLAYING source=$currentSourceLabel")
                    },
                    onVideoStatistics = { frames, seconds ->
                        // Ignore advertisements and loading screens. Only a source
                        // with the foreground VIDEO_STARTED event may influence the TV.
                        if (confirmedPlayback) frameMatcher?.observe(frames, seconds)
                    },
                    onMediaActivity = {
                        if (!geckoMediaActivity) {
                            geckoMediaActivity = true
                            Log.i("JA_SOURCES", "SOURCE_MEDIA_ACTIVITY source=$currentSourceLabel")
                        }
                        // Actual fatal errors still trigger the configured fallback.
                    },
                    onTopLevelRedirect = onTopLevelRedirect,
                    onFatalError = { reason ->
                        if (!confirmedPlayback) onSourceFailure(source, reason)
                        else {
                            // Playback once succeeded; later errors are not proof the
                            // entire title is unavailable on this source.
                            Log.w("JA_SOURCES", "SOURCE_INTERRUPTED source=$currentSourceLabel reason=$reason")
                        }
                    }
                )
                if (showBack) {
                    TvFocusButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart)) { Text("Back") }
                }
                // Useful when the remote provider renders an opaque white or
                // TLS error screen which GeckoView cannot report from an iframe.
                // Shown only with diagnostics enabled to avoid permanently
                // overlaying working player controls or distracting TV users.
                if (showDiagnostics && hasNextSource) {
                    TvFocusButton(
                        onClick = onTryNextSource,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF202832))
                    ) { Text("Next Server", fontSize = 12.sp, color = Color.White) }
                }
            }
        }
    }
}


/** Persist player overlay preferences between JA launches. */
private class PlayerControlSettings(context: Context) {
    private val prefs = context.getSharedPreferences("ja_player_controls", Context.MODE_PRIVATE)
    var showBack: Boolean
        get() = prefs.getBoolean("back", false)
        set(value) { prefs.edit().putBoolean("back", value).apply() }
    var showRestore: Boolean
        get() = prefs.getBoolean("restore", false)
        set(value) { prefs.edit().putBoolean("restore", value).apply() }
    var showServerChecks: Boolean
        get() = prefs.getBoolean("show_server_checks", true)
        set(value) { prefs.edit().putBoolean("show_server_checks", value).apply() }
    var showPlayerDiagnostics: Boolean
        get() = prefs.getBoolean("show_player_diagnostics", false)
        set(value) { prefs.edit().putBoolean("show_player_diagnostics", value).apply() }
    var matchTvFrameRate: Boolean
        get() = prefs.getBoolean("tv_match_frame_rate", false)
        set(value) { prefs.edit().putBoolean("tv_match_frame_rate", value).apply() }
    var adBlockerMode: String
        get() = JaGeckoAdBlockers.normalized(prefs.getString("ad_blocker_mode", JaGeckoAdBlockers.UBLOCK) ?: JaGeckoAdBlockers.UBLOCK)
        set(value) { prefs.edit().putString("ad_blocker_mode", JaGeckoAdBlockers.normalized(value)).apply() }
}
