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
import kotlinx.coroutines.delay

private val bg = Color(0xFF12171D)
private val accent = Color(0xFFE5A43B)
private data class DetailReturnPoint(val title: TitleItem?, val person: TmdbPersonCredit?)
/** Prewarm the SAME GeckoRuntime used by the embedded player.
 * The Firefox add-on persists across restarts; Gecko only downloads it once.
 * Never hold up the catalog UI or title selection while it initializes.
 */
private fun prewarmPlayerAdBlocker(context: Context, mode: String) {
    try {
        val runtime = JaGeckoRuntime.get(context)
        prepareJaPlayerExtensions(runtime) { ready ->
            Log.i("JA_STARTUP", "APP_EXTENSIONS_PREWARM ready=$ready")
        }
        JaGeckoAdBlockers.prepare(runtime, mode) { status ->
            Log.i("JA_ADBLOCK", "APP_PREWARM mode=$mode status=$status")
        }
    } catch (error: Exception) {
        Log.w("JA_ADBLOCK", "APP_PREWARM_FAILED mode=$mode", error)
        // The GeckoView player can still start with its native popup safeguards.
    }
}


/**
 * Only the three entries in PlaybackRegistry may be considered for playback.
 * This guard also blocks stale domain-ledger aliases and old preference IDs.
 */
private val jaActiveSourceIds = listOf("vidsrc", "vidfast-vc", "cinejoy")

private fun isJaPlaybackSourceEnabled(id: String): Boolean = id in jaActiveSourceIds

private fun jaAllowedSourceChoices(registered: List<PlaybackChoice>): List<PlaybackChoice> =
    registered.filter { it.id in jaActiveSourceIds }
        .distinctBy { it.id }
        .sortedBy { jaActiveSourceIds.indexOf(it.id) }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("JA_STARTUP", "APP_BUILD versionName=${BuildConfig.VERSION_NAME} versionCode=${BuildConfig.VERSION_CODE}")
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
    val serverPlaybackHistory = remember { ServerPlaybackHistory(appContext) }
    // User-triggered reset layer; leaves JA's existing history database untouched.
    val preferredSourceReset = remember { PreferredSourceReset(appContext) }
    val scope = rememberCoroutineScope()
    val settings = remember { PlayerControlSettings(context = appContext) }
    // Persist the order in which titles were favorited separately from the
    // watch-history timestamp, which changes whenever an episode is played.
    val favoritesOrder = remember { FavoritesDisplayOrder(appContext) }
    var showBack by remember { mutableStateOf(settings.showBack) }
    var showRestore by remember { mutableStateOf(settings.showRestore) }
    var showPlayerDiagnostics by remember { mutableStateOf(settings.showPlayerDiagnostics) }
    // Match frame rate by default on televisions; phones remain disabled.
    // Explicit prior choices persist. Resolution matching is a separate opt-in.
    var matchTvFrameRate by remember { mutableStateOf(settings.matchTvFrameRate) }
    var matchTvResolution by remember { mutableStateOf(settings.matchTvResolution) }
    var adBlockerMode by remember { mutableStateOf(settings.adBlockerMode) }
    val blockerStatus by JaGeckoAdBlockers.status.collectAsState()
    var blockerTools by remember { mutableStateOf<JaGeckoAdBlockers.ManagementPages?>(null) }
    var blockerToolsError by remember { mutableStateOf<String?>(null) }
    var confirmPreferredSourceReset by remember { mutableStateOf(false) }
    var preferredSourceResetStatus by remember { mutableStateOf<String?>(null) }
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
    // A session generation ensures fallback gets fresh GeckoView and display matching.
    var playbackSessionId by remember { mutableIntStateOf(0) }
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
                    val genreType = if (tab == "Movies") "movie" else "tv"
                    genreRows = repo.cachedGenreRows(genreType).orEmpty()
                    genresLoading = genreRows.isEmpty()
                    error = null
                    try { genreRows = repo.genreRows(genreType) { partial ->
                        genreRows = partial
                        genresLoading = false
                    } }
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
        // Details load TMDB metadata only; no provider HTTP or hidden-video tests.
        Log.i("JA_SERVERS", "DETAILS_NO_PROBES type=${item.type} tmdb=${item.id}")
        if (item.type == "tv") scope.launch {
            try {
                val loadedSeasons = repo.seasons(item.id)
                val selectedSeason = loadedSeasons.firstOrNull() ?: 1
                val loadedEpisodes = repo.episodes(item.id, selectedSeason)
                // Ignore an old response if the user has already navigated away.
                if (selected?.id == item.id && selected?.type == item.type) {
                    seasons = loadedSeasons; season = selectedSeason; episodes = loadedEpisodes
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
    var resumePrompt by remember { mutableStateOf<PlaybackRequest?>(null) }
    var resumePositionMs by remember { mutableStateOf(0L) }
    var resumeLookupBusy by remember { mutableStateOf(false) }
    fun startPlayback(item: TitleItem, s: Int = 0, e: Int = 0, startAtMs: Long = 0L) {
        // Direct playback: no HTTP preflight, no detached verification, and no
        // preference/HTTP ranking. Each failed player falls back locally.
        resumePositionMs = startAtMs
        playbackSessionId += 1
        error = null
        playback = null
        playbackChoices = emptyList()
        playbackChoiceIndex = 0
        playbackLoading = true
        Log.d("JA_PLAYBACK", "Play clicked: ${item.title} (${item.type}, TMDB ${item.id})")
        scope.launch {
            try {
                val r = PlaybackRequest(item, s, e)
                request = r
                // The registry is synchronous: no network wait before GeckoView.
                val registered = provider.choices(r)
                val candidates = jaAllowedSourceChoices(registered)
                Log.i("JA_SERVERS", "SOURCE_ALLOWLIST type=${r.media.type} tmdb=${r.media.id} " +
                    "registered=${registered.size} enabled=${candidates.size} " +
                    "order=${candidates.joinToString(",") { it.id }}")
                // Use the reset-style start order on EVERY new title. The
                // registry/allowlist already sorts VidSrc -> VidFast -> CineJoy.
                // Keep playback history for diagnostics, but do not let learned
                // successes or HTTP responses unexpectedly select a different
                // startup source. The reset button still clears saved history.
                val choices = candidates
                Log.i("JA_SERVERS", "SOURCE_DEFAULT_RESET_ORDER " +
                    "type=${r.media.type} tmdb=${r.media.id} " +
                    "order=${choices.joinToString(",") { it.id }} " +
                    "historyRank=ignored")
                if (choices.isEmpty()) {
                    error = "No configured playback source for this TMDB title."
                    return@launch
                }
                playbackChoices = choices
                playbackChoiceIndex = 0
                playback = choices.first().source
                Log.i("JA_SOURCES", "SOURCE_START id=${choices.first().id} count=${choices.size} " +
                    "type=${item.type} tmdb=${item.id} scan=disabled")
                val old = dao.find(item.id, item.type, s, e)
                dao.save(WatchItem(item.id, item.type, s, e, item.title, item.poster, old?.favorite ?: false, old?.positionMs ?: 0))
                refreshHistory()
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (ex: Exception) {
                error = "Playback lookup failed: ${ex.message ?: "Unknown error"}"
                Log.e("JA_PLAYBACK", "Playback lookup failed", ex)
            } finally {
                playbackLoading = false
            }
        }
    }
    fun play(item: TitleItem, s: Int = 0, e: Int = 0) {
        if (resumeLookupBusy) return
        resumeLookupBusy = true
        scope.launch {
            try {
                val saved = dao.find(item.id, item.type, s, e)
                if (saved != null && saved.positionMs >= 5_000L) {
                    resumePositionMs = saved.positionMs
                    resumePrompt = PlaybackRequest(item, s, e)
                } else startPlayback(item, s, e)
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (problem: Exception) { error = "Could not load saved progress. Please try again." }
            finally { resumeLookupBusy = false }
        }
    }
    resumePrompt?.let { pending ->
        AlertDialog(
            onDismissRequest = { resumePrompt = null },
            title = { Text("Continue watching?") },
            text = { Text("Resume ${pending.media.title} at ${resumePositionMs / 60_000}:${((resumePositionMs / 1000) % 60).toString().padStart(2, '0')}?") },
            confirmButton = { TextButton(onClick = {
                resumePrompt = null
                startPlayback(pending.media, pending.season, pending.episode, resumePositionMs)
            }) { Text("Resume") } },
            dismissButton = { TextButton(onClick = {
                resumePrompt = null
                scope.launch {
                    try {
                        dao.updateProgress(pending.media.id, pending.media.type, pending.season, pending.episode, 0L, System.currentTimeMillis())
                        startPlayback(pending.media, pending.season, pending.episode, 0L)
                    } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                    catch (problem: Exception) { error = "Could not reset saved progress. Please try again." }
                }
            }) { Text("Start over") } }
        )
    }
    fun nextPlaybackSource(reason: String, recordFailure: Boolean = true) {
        // No HTTP checks here either. All three sources are known from the
        // registry; actual GeckoView failures decide when to try the next one.
        val failed = playbackChoices.getOrNull(playbackChoiceIndex) ?: return
        if (recordFailure) request?.let { current ->
            serverPlaybackHistory.recordExplicitFailure(current, failed.id, reason)
        }
        val next = playbackChoiceIndex + 1
        if (next >= playbackChoices.size) {
            Log.w("JA_SOURCES", "SOURCES_EXHAUSTED reason=$reason")
            return
        }
        val target = playbackChoices[next]
        Log.w("JA_SOURCES", "SOURCE_FALLBACK from=${failed.id} to=${target.id} " +
            "reason=$reason scan=disabled")
        playbackChoiceIndex = next
        playback = target.source
    }
    BackHandler(playback != null || selected != null || selectedPerson != null || (tab == "Search" && searchGenre != null)) {
        when {
            playback != null -> {
                Log.i("JA_SOURCES", "PLAYER_CLOSED reason=ANDROID_BACK")
                playback = null
            }
            selected != null || selectedPerson != null -> backFromDetails()
            searchGenre != null -> exitGenre()
        }
    }
    blockerTools?.let { pages ->
        JaBlockerTools(pages = pages, onClose = { blockerTools = null })
        return
    }
    // Playback occupies the entire activity instead of the catalog's padded column.
    if (playback != null) {
        // Each fallback must own a fresh Compose player generation. Dispose
        // the previous GeckoSession and its frame-rate matcher rather than
        // inheriting frame statistics or display decisions from that server.
        // The source index is included because the preferred-source order may
        // differ after learning/reset and the URL need not be the only key.
        val playingSourceId = playbackChoices.getOrNull(playbackChoiceIndex)?.id ?: "unknown"
        key(playbackSessionId, playbackChoiceIndex, playingSourceId) {
        PlayerScreen(
            source = playback!!, request = request, dao = dao,
            resumePositionMs = resumePositionMs,
            onRetry = { playbackSessionId += 1 },
            onDisableBlockerAndRetry = {
                adBlockerMode = JaGeckoAdBlockers.OFF
                settings.adBlockerMode = JaGeckoAdBlockers.OFF
                playbackSessionId += 1
            },
            onPositionChanged = { resumePositionMs = it },
            showBack = showBack, showRestore = showRestore,
            showDiagnostics = showPlayerDiagnostics,
            matchTvFrameRate = matchTvFrameRate,
            matchTvResolution = matchTvResolution,
            adBlockerMode = adBlockerMode,
            currentSourceLabel = playbackChoices.getOrNull(playbackChoiceIndex)?.label ?: "Source",
            currentSourceId = playingSourceId,
            hasNextSource = playbackChoiceIndex < playbackChoices.lastIndex,
            onTryNextSource = { nextPlaybackSource("MANUAL_NEXT_SERVER", recordFailure = false) },
            onSourceStarted = { startedSource ->
                val current = playbackChoices.getOrNull(playbackChoiceIndex)
                val activeRequest = request
                if (current?.source == startedSource && activeRequest != null) {
                    // Alias hosts are only promoted after foreground video
                    // activity, never just an HTTP 301 or a headless probe.
                    // Keep CineJoy's stable ID in playback history.
                    val canonicalId = if (current.id == "cinejoy") {
                        "cinejoy"
                    } else {
                        serverDomainLedger.onMediaStarted(
                            current, provider.choices(activeRequest)
                                .filter { isJaPlaybackSourceEnabled(it.id) }
                        )
                    }
                    serverPlaybackHistory.recordVideoStarted(activeRequest, canonicalId)
                    // Relearn only successes observed AFTER the global reset.
                    preferredSourceReset.recordVideoStarted(activeRequest, canonicalId)
                    Log.i("JA_SERVERS", "VIDEO_STARTED_RECORDED provider=$canonicalId " +
                        "series=${activeRequest.media.type == "tv"} tmdb=${activeRequest.media.id} " +
                        "season=${activeRequest.season} episode=${activeRequest.episode}")
                }
            },
            onTopLevelRedirect = { target ->
                val active = playbackChoices.getOrNull(playbackChoiceIndex)
                val playingRequest = request
                if (active != null && playingRequest != null && active.id != "cinejoy") {
                    serverDomainLedger.observeNavigationRedirect(
                        active, target, provider.choices(playingRequest)
                            .filter { isJaPlaybackSourceEnabled(it.id) }
                    )
                }
            },
            onSourceFailure = { failedSource, reason ->
                // Ignore delayed reports emitted after a different player opened.
                if (playback == failedSource) request?.let {
                    serverPlaybackHistory.recordExplicitFailure(it, playingSourceId, reason)
                }
            },
            onClose = {
                Log.i("JA_SOURCES", "PLAYER_CLOSED reason=PLAYER_BACK_BUTTON")
                playback = null
            }
        )
        }
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
        if (playbackLoading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Opening selected source…") }
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
                Text("Player diagnostics", style = MaterialTheme.typography.titleLarge, color = Color.White)
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
                    Text("Match TV frame rate during playback", color = Color.White)
                }
                Text(
                    "Enabled by default on Android TV; disabled on phones. JA measures video frames to " +
                        "request a compatible display refresh rate. A mode change may briefly blank the screen. " +
                        "Your previous display preference is restored when playback closes.",
                    color = Color.LightGray, style = MaterialTheme.typography.bodySmall
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = matchTvResolution,
                        enabled = matchTvFrameRate,
                        onCheckedChange = { enabled ->
                            matchTvResolution = enabled
                            settings.matchTvResolution = enabled
                        }
                    )
                    Text("Match TV output resolution to video (experimental)", color = Color.White)
                }
                Text(
                    "Optional Android TV test: a 1080-class video can request 1080p at its matching frame rate " +
                        "instead of keeping the TV's 4K output. Frame-rate matching takes priority. " +
                        "Resolution matching is off by default; use it only if it looks better on your TV. " +
                        "The prior display preference is restored on exit.",
                    color = Color.LightGray, style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(16.dp))
                Text("Embedded player ad blocking", style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text(
                    "Choose ONE Firefox ad blocker. GeckoView initializes it when JA opens, and downloads the Mozilla-signed add-on only if needed. " +
                         "For a white-screen test, select Off to disable both uBlock and AdGuard. Popup windows and JS prompts remain blocked. Restart playback after changing this setting.",
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
                Text("Status: ${blockerStatus.message}", color =
                    if (blockerStatus.phase == JaGeckoAdBlockers.Phase.FAILED ||
                        blockerStatus.phase == JaGeckoAdBlockers.Phase.WARNING) Color(0xFFFFC48A) else Color.LightGray,
                    style = MaterialTheme.typography.bodySmall)
                Text("Enabled means Gecko started the extension; it does not verify its filter engine. " +
                    "Switching to Off waits for both filtering extensions to be disabled.",
                    color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                if (adBlockerMode == JaGeckoAdBlockers.UBLOCK) {
                    Button(onClick = {
                        blockerToolsError = null
                        JaGeckoAdBlockers.managementPages(appContext) { pages, problem ->
                            blockerTools = pages
                            blockerToolsError = problem
                        }
                    }, enabled = blockerStatus.mode == JaGeckoAdBlockers.UBLOCK &&
                        blockerStatus.phase != JaGeckoAdBlockers.Phase.STARTING) {
                        Text("Open uBlock dashboard / logger")
                    }
                }
                blockerToolsError?.let { Text(it, color = Color(0xFFFFC48A)) }
                Spacer(Modifier.height(16.dp))
                Text("Preferred playback sources", style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text(
                    "Reset source choices for every movie and episode. Default order: " +
                        "1. VidSrc (vidsrc.sh), 2. VidFast (vidfast.vc), " +
                        "3. CineJoy (cinejoy.pk). No other providers are enabled. " +
                        "JA always starts new titles in this order; previous successes cannot reorder it. " +
                        "The Reset button also clears saved source preferences. Watch progress stays intact.",
                    color = Color.LightGray, style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                TvFocusButton(
                    onClick = { confirmPreferredSourceReset = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                ) { Text("Reset preferred sources", color = Color.White) }
                preferredSourceResetStatus?.let {
                    Text(it, color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(12.dp))
                Text("Settings are saved on this device.", color = Color.LightGray)
                if (confirmPreferredSourceReset) {
                    AlertDialog(
                        onDismissRequest = { confirmPreferredSourceReset = false },
                        title = { Text("Reset preferred sources?") },
                        text = { Text(
                            "All titles will start with VidSrc (vidsrc.sh), followed by " +
                                "VidFast (vidfast.vc), then CineJoy (cinejoy.pk). " +
                                 "Previously learned source choices are not used for the " +
                                "startup order and saved preferences will be cleared. " +
                                "Watch progress is preserved."
                        ) },
                        confirmButton = {
                            TvFocusButton(
                                onClick = {
                                    val saved = preferredSourceReset.resetAll()
                                    preferredSourceResetStatus = if (saved)
                                        "Preferred sources reset: VidSrc, VidFast, CineJoy."
                                    else "Reset could not be saved. Please retry."
                                    confirmPreferredSourceReset = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = accent)
                            ) { Text("Reset all titles", color = Color.Black) }
                        },
                        dismissButton = {
                            TvFocusButton(
                                onClick = { confirmPreferredSourceReset = false },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                            ) { Text("Cancel", color = Color.White) }
                        }
                    )
                }
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
    matchTvResolution: Boolean = false,
    adBlockerMode: String = JaGeckoAdBlockers.UBLOCK,
    currentSourceLabel: String = "Source",
    currentSourceId: String = "unknown",
    hasNextSource: Boolean = false,
    onTryNextSource: () -> Unit = {},
    onRetry: () -> Unit = {},
    onDisableBlockerAndRetry: () -> Unit = {},
    onPositionChanged: (Long) -> Unit = {},
    resumePositionMs: Long = 0L,
    onSourceStarted: (PlaybackSource) -> Unit = { _ -> },
    onTopLevelRedirect: (String) -> Unit = {},
    onSourceFailure: (PlaybackSource, String) -> Unit = { _, _ -> },
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playbackActivity = context as? Activity
    val frameMatcher = remember(source, playbackActivity, matchTvFrameRate, matchTvResolution) {
        if (matchTvFrameRate && playbackActivity != null) {
            JaTvFrameRateMatcher(playbackActivity, preferSourceResolution = matchTvResolution)
        } else null
    }
    DisposableEffect(frameMatcher, currentSourceId, source, matchTvFrameRate, matchTvResolution) {
        Log.i("JA_FRAME_RATE", "MATCHER_SOURCE_ATTACH source=$currentSourceId " +
            "enabled=${frameMatcher != null} setting=$matchTvFrameRate " +
            "resolution=$matchTvResolution matcher=${frameMatcher?.let { System.identityHashCode(it) } ?: 0}")
        onDispose {
            Log.i("JA_FRAME_RATE", "MATCHER_SOURCE_RELEASE source=$currentSourceId " +
                "enabled=${frameMatcher != null} matcher=${frameMatcher?.let { System.identityHashCode(it) } ?: 0}")
            frameMatcher?.close()
        }
    }
    var playerError by remember(source) { mutableStateOf<String?>(null) }
    var recoveryReason by remember(source) { mutableStateOf<String?>(null) }
    var confirmedPlayback by remember(source) { mutableStateOf(false) }
    // A running media session is evidence that the embed is active, even if
    // the nested video frame never emits VIDEO_STARTED. Do not mistake it for
    // proof that the requested title is available (ads may also play media).
    var geckoMediaActivity by remember(source) { mutableStateOf(false) }
    // The 45-second media-start clock begins ONLY once Gecko has registered
    // its monitors and is about to issue loadUri() for the selected source.
    // Cold-start extension setup must not consume the playback timeout.
    var embedLoadRequested by remember(source) { mutableStateOf(false) }
    // Suppress a second failure after an explicit fatal error (including the
    // last-provider case where the player remains visible after exhaustion).
    var startupFailureReported by remember(source) { mutableStateOf(false) }
    // A white page may finish its top-level navigation without ever starting
    // video or emitting a fatal error. Independently watch for *any* genuine
    // playback activity (not just an HTML video element / PageStop). This
    // preserves the legacy behavior for embeds that play successfully but do
    // not expose VIDEO_STARTED to the source-status extension.
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
                        recoveryReason = "The native player could not play this source."
                        onSourceFailure(source, "NATIVE_PLAYBACK_ERROR")
                    }
                }
                player.addListener(listener)
                onDispose { player.removeListener(listener) }
            }
            LaunchedEffect(player, request) {
                val r = request ?: return@LaunchedEffect
                if (resumePositionMs > 0) player.seekTo(resumePositionMs)
                while (true) {
                    delay(5_000L)
                    val position = player.currentPosition.coerceAtLeast(0)
                    onPositionChanged(position)
                    PlaybackProgress.save(dao, r, position)
                }
            }
            DisposableEffect(player, request) {
                onDispose {
                    val r = request
                    PlaybackProgress.save(dao, r, player.currentPosition.coerceAtLeast(0))
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
            // The effect is bound to the active source and composed player.
            // Changing sources, leaving the player, or pressing Back cancels it.
            // A page that fires PageStop but never plays media must not stay
            // on a white screen indefinitely. Do not treat hasVideo/iframe
            // connection alone as playback success.
            LaunchedEffect(source, request, embedLoadRequested, confirmedPlayback) {
                if (!embedLoadRequested) {
                    delay(95_000L)
                    if (!embedLoadRequested) recoveryReason = "Player initialization is taking too long."
                } else if (!confirmedPlayback) {
                    delay(45_000L)
                    if (!confirmedPlayback) {
                        recoveryReason = "Playback has not started. Retry or choose another server."
                        JaGeckoAdBlockers.reportPlaybackProblem(adBlockerMode, "VIDEO_START_TIMEOUT")
                        Log.w("JA_SOURCES", "VIDEO_START_TIMEOUT blocker=$adBlockerMode filterHealth=UNVERIFIED")
                    }
                }
            }
            Box(Modifier.fillMaxSize()) {
                GeckoExperimentalPlayer(
                    url = source.url,
                    resumePositionMs = resumePositionMs,
                    onProgress = { position ->
                        onPositionChanged(position)
                        PlaybackProgress.save(dao, request, position)
                    },
                    onPlaybackStalled = {
                        recoveryReason = "Playback stopped advancing. Retry or choose another server."
                    },
                    showRestore = showRestore,
                    showDiagnostics = showDiagnostics,
                    adBlockerMode = adBlockerMode,
                    diagnosticsLabel = currentSourceLabel,
                    onEmbedLoadRequested = {
                        if (!embedLoadRequested) {
                            embedLoadRequested = true
                            Log.i("JA_SOURCES", "VIDEO_START_WATCHDOG_LOAD_READY source=$currentSourceId")
                        }
                    },
                    onMediaStarted = {
                        if (!confirmedPlayback) onSourceStarted(source)
                        confirmedPlayback = true
                        recoveryReason = null
                        Log.i("JA_SOURCES", "SOURCE_PLAYING source=$currentSourceLabel")
                    },
                    // Stats are delivered directly to the current frame matcher
                    // from this GeckoView generation, after VIDEO_STARTED.
                    // This avoids a second activity-level callback whose
                    // lifecycle could diverge from the active GeckoSession.
                    frameRateMatcher = frameMatcher,
                    onMediaActivity = {
                        if (!geckoMediaActivity) {
                            geckoMediaActivity = true
                            Log.i("JA_SOURCES", "SOURCE_MEDIA_ACTIVITY source=$currentSourceLabel")
                        }
                        // Actual fatal errors still trigger the configured fallback.
                    },
                    onTopLevelRedirect = onTopLevelRedirect,
                    onFatalError = { reason ->
                        if (!confirmedPlayback && !startupFailureReported) {
                            startupFailureReported = true
                            recoveryReason = "This source could not start playback."
                            JaGeckoAdBlockers.reportPlaybackProblem(adBlockerMode, reason)
                            onSourceFailure(source, reason)
                        } else if (confirmedPlayback) {
                            // Playback once succeeded; later errors are not proof the
                            // entire title is unavailable on this source.
                            recoveryReason = "Playback was interrupted."
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
    recoveryReason?.let { reason ->
        AlertDialog(
            onDismissRequest = { recoveryReason = null },
            title = { Text("Playback needs attention") },
            text = { Text(reason) },
            confirmButton = {
                Column {
                    TextButton(onClick = onRetry) { Text("Retry") }
                    if (adBlockerMode != JaGeckoAdBlockers.OFF) {
                        TextButton(onClick = onDisableBlockerAndRetry) { Text("Turn ad blocking Off and retry") }
                    }
                }
            },
            dismissButton = {
                Row {
                    if (hasNextSource) TextButton(onClick = onTryNextSource) { Text("Next Server") }
                    TextButton(onClick = onClose) { Text("Back") }
                }
            }
        )
    }
}


/** Persist player overlay preferences between JA launches. */
private class PlayerControlSettings(context: Context) {
    private val prefs = context.getSharedPreferences("ja_player_controls", Context.MODE_PRIVATE)
    private val isTelevision =
        (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    var showBack: Boolean
        get() = prefs.getBoolean("back", false)
        set(value) { prefs.edit().putBoolean("back", value).apply() }
    var showRestore: Boolean
        get() = prefs.getBoolean("restore", false)
        set(value) { prefs.edit().putBoolean("restore", value).apply() }
    var showPlayerDiagnostics: Boolean
        get() = prefs.getBoolean("show_player_diagnostics", false)
        set(value) { prefs.edit().putBoolean("show_player_diagnostics", value).apply() }
    var matchTvFrameRate: Boolean
        get() = isTelevision && prefs.getBoolean("tv_match_frame_rate", true)
        set(value) { prefs.edit().putBoolean("tv_match_frame_rate", value).apply() }
    var matchTvResolution: Boolean
        get() = isTelevision && prefs.getBoolean("tv_match_resolution", false)
        set(value) { prefs.edit().putBoolean("tv_match_resolution", value).apply() }
    var adBlockerMode: String
        get() = JaGeckoAdBlockers.normalized(prefs.getString("ad_blocker_mode", JaGeckoAdBlockers.UBLOCK) ?: JaGeckoAdBlockers.UBLOCK)
        set(value) { prefs.edit().putString("ad_blocker_mode", JaGeckoAdBlockers.normalized(value)).apply() }
}
