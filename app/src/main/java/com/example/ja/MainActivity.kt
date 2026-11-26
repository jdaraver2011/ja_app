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
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val bg = Color(0xFF12171D)
private val accent = Color(0xFFE5A43B)
private data class DetailReturnPoint(
    val title: TitleItem?, val person: TmdbPersonCredit?, val castId: Int? = null
)
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
 * Only the enabled entries in PlaybackRegistry may be considered for playback.
 * This guard also blocks stale domain-ledger aliases and old preference IDs.
 */
private val jaActiveSourceIds = listOf("vidsrc", "vidfast-vc", "scottsmedia", "movy", "7movies", "cinejoy")

private fun isJaPlaybackSourceEnabled(id: String): Boolean = id in jaActiveSourceIds

private fun jaAllowedSourceChoices(registered: List<PlaybackChoice>): List<PlaybackChoice> =
    registered.filter { it.id in jaActiveSourceIds }
        .distinctBy { it.id }
        .sortedBy { jaActiveSourceIds.indexOf(it.id) }

class MainActivity : ComponentActivity() {
    private var explicitExit = false
    private var musicOpenRequest by mutableIntStateOf(0)
    private var authCallbackUri by mutableStateOf<String?>(null)

    private fun handleMusicVoiceSearch(intent: Intent) {
        if (intent.action == "android.media.action.MEDIA_PLAY_FROM_SEARCH") {
            musicOpenRequest++
            JaMusicLabService.playSearch(this, intent.getStringExtra(android.app.SearchManager.QUERY).orEmpty())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.dataString?.let { authCallbackUri = it }
        if (intent.getBooleanExtra(JaMusicService.OPEN_MUSIC, false)) musicOpenRequest++
        handleMusicVoiceSearch(intent)
    }

    fun consumeAuthCallback() { authCallbackUri = null }

    fun closeFromDoubleBack() {
        if (explicitExit) return
        explicitExit = true
        finishAndRemoveTask()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Home, app switching and configuration changes never take this path.
        if (explicitExit) {
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            val terminate = Runnable { android.os.Process.killProcess(android.os.Process.myPid()) }
            main.postDelayed(terminate, 5000L)
            JaGeckoRuntime.shutdownForExit { main.post(terminate) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("JA_STARTUP", "APP_BUILD versionName=${BuildConfig.VERSION_NAME} versionCode=${BuildConfig.VERSION_CODE}")
        JaLiveTvUpdates.schedule(applicationContext)
        val db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "ja.db").build()
        if (intent.getBooleanExtra(JaMusicService.OPEN_MUSIC, false)) musicOpenRequest++
        intent.dataString?.let { authCallbackUri = it }
        handleMusicVoiceSearch(intent)
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = accent, background = bg, surface = Color(0xFF202832), onBackground = Color.White, onSurface = Color.White, onSurfaceVariant = Color.LightGray)) { StreamingApp(db.dao(), musicOpenRequest, authCallbackUri) } }
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

@Composable fun StreamingApp(dao: WatchDao, openMusicRequest: Int = 0, authCallbackUri: String? = null) {
    val appContext = LocalContext.current.applicationContext
    val mainActivity = LocalContext.current as? MainActivity
    val account = remember { JaAccountSync(appContext, dao) }
    val testAccessPrefs = remember(appContext) { appContext.getSharedPreferences("ja_test_access", Context.MODE_PRIVATE) }
    val testAccessVersion = BuildConfig.VERSION_CODE
    var testUnlocked by remember { mutableStateOf(testAccessPrefs.getInt("unlocked_version", -1) == testAccessVersion) }
    var signedIn by remember { mutableStateOf(account.session() != null || testUnlocked) }
    if (signedIn) {
        StreamingAppContent(dao, openMusicRequest, onSignedOut = { signedIn = testUnlocked })
    } else {
        JaRequiredAccountGate(dao, authCallbackUri,
            onConsumeAuthCallback = { mainActivity?.consumeAuthCallback() },
            onTestUnlock = {
                testAccessPrefs.edit().putInt("unlocked_version", testAccessVersion).apply()
                testUnlocked = true
                signedIn = true
            },
            onAuthenticated = { signedIn = true })
    }
}

@Composable private fun StreamingAppContent(dao: WatchDao, openMusicRequest: Int, onSignedOut: () -> Unit) {
    val activity = LocalContext.current as? MainActivity
    val appContext = LocalContext.current.applicationContext
    val titleReturnFocus = remember { JaTitleReturnFocus() }
    val exitGate = remember { JaDoubleBackExit() }
    var exiting by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) exitGate.reset()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    remember { WatchedStore.initialize(appContext); true }
    val watched by WatchedStore.watched.collectAsState()
    remember { JaVidSrcCatalog.initialize(appContext); true }
    val vidSrcSnapshot by JaVidSrcCatalog.snapshot.collectAsState()
    val dailyVidSrcDownloads by JaVidSrcCatalog.dailyDownloads.collectAsState()
    val vidSrcQuality by JaVidSrcCatalog.quality.collectAsState()
    val vidSrcPromotions by JaVidSrcCatalog.promotions.collectAsState()
    val sourceDataVersion = vidSrcSnapshot.generation to vidSrcPromotions
    fun sourcesCurrent(version: Pair<Long,Long>) =
        JaVidSrcCatalog.snapshot.value.generation == version.first && JaVidSrcCatalog.promotions.value == version.second
    fun marked(item: TitleItem) = item.copy(watched = WatchedStore.key(item.id, item.type) in watched)
    var pendingEpisodeFocus by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var pendingPlayFocus by remember { mutableStateOf(false) }
    var playbackReturnToPlay by remember { mutableStateOf(true) }
    var playbackEpisodeTarget by remember { mutableStateOf<Pair<Int,Int>?>(null) }
    var pendingCastFocus by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val catalogueRefresh = remember { JaCatalogRefresh(java.time.LocalDate.now()) }
    var catalogueDate by remember { mutableStateOf(catalogueRefresh.day()) }
    var catalogueGeneration by remember { mutableLongStateOf(catalogueRefresh.generation()) }
    // A real background return refreshes same-day TMDB corrections as well as dates.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> { catalogueRefresh.background(); JaVidSrcCatalog.foreground(false) }
                Lifecycle.Event.ON_START -> {
                    JaVidSrcCatalog.foreground(true)
                    catalogueRefresh.foreground(java.time.LocalDate.now())
                    catalogueDate = catalogueRefresh.day()
                    catalogueGeneration = catalogueRefresh.generation()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000L)
            catalogueRefresh.tick(java.time.LocalDate.now())
            catalogueDate = catalogueRefresh.day()
            catalogueGeneration = catalogueRefresh.generation()
        }
    }
    val repo = remember(catalogueDate, catalogueGeneration) { TmdbRepository() }
    val movieCatalogueRepo = remember(catalogueDate, catalogueGeneration, sourceDataVersion) { TmdbRepository() }
    val detailsRepo = remember { TmdbDetailsRepository() }
    val peopleRepo = remember { TmdbPersonRepository() }
    val searchRepo = remember { TmdbSearchRepository() }
    val provider = remember { PlaybackRegistry() }
    val serverDomainLedger = remember { ServerDomainLedger(appContext) }
    val serverPlaybackHistory = remember { ServerPlaybackHistory(appContext) }
    // User-triggered reset layer; leaves JA's existing history database untouched.
    val preferredSourceReset = remember { PreferredSourceReset(appContext) }
    val scope = rememberCoroutineScope()
    val accountSync = remember { JaAccountSync(appContext, dao) }
    val cloudApplyGeneration by JaAccountSync.cloudAppliedGeneration.collectAsState()
    DisposableEffect(lifecycleOwner, accountSync) {
        val syncPrefs = listOf(
            "ja_music_library", "ja_live_favorites", "ja_favorite_display_order_v1", "ja_watched_v1",
            "ja_player_controls", "ja_weather", "ja_playback_server_history_v1",
            "ja_source_preferences_reset", JaSearchHistory.PREFS, "ja_music_lab_session"
        )
        val registered = mutableListOf<Pair<android.content.SharedPreferences, android.content.SharedPreferences.OnSharedPreferenceChangeListener>>()
        syncPrefs.forEach { name ->
            val prefs = appContext.getSharedPreferences(name, Context.MODE_PRIVATE)
            val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
                val key = changedKey.orEmpty()
                val included = when (name) {
                    "ja_weather" -> key in setOf("weatherstar", "weatherstar-sound", "weatherstar-volume", "music-choice", "custom-music", "zip")
                    "ja_live_favorites" -> key == "ids" || key == "order" || key.startsWith("group-order:") || key.startsWith("source-group-order:")
                    "ja_favorite_display_order_v1" -> key == "movie-ids" || key == "tv-ids"
                    else -> true
                }
                if (included && !JaAccountSync.shouldSuppressAutoSync()) JaAccountSyncWorker.enqueue(appContext)
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            registered += prefs to listener
        }
        val rowsJob = scope.launch {
            dao.observeAll().drop(1).collect {
                if (!JaAccountSync.shouldSuppressAutoSync()) JaAccountSyncWorker.enqueue(appContext)
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START && accountSync.session() != null) {
                scope.launch {
                    val outcome = runCatching { accountSync.sync() }
                    if (outcome.exceptionOrNull()?.message?.contains("awaiting administrator approval") == true) {
                        accountSync.signOut()
                        onSignedOut()
                    }
                }
            } else if (event == Lifecycle.Event.ON_STOP) {
                JaAccountSyncWorker.enqueue(appContext, delayMs = 0L)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            registered.forEach { (prefs, listener) -> prefs.unregisterOnSharedPreferenceChangeListener(listener) }
            rowsJob.cancel()
        }
    }
    val settings = remember { PlayerControlSettings(context = appContext) }
    // Persist the order in which titles were favorited separately from the
    // watch-history timestamp, which changes whenever an episode is played.
    val favoritesOrder = remember { FavoritesDisplayOrder(appContext) }
    var showMusicTab by remember { mutableStateOf(settings.showMusicTab) }
    var showIptvTab by remember { mutableStateOf(settings.showIptvTab) }
    var musicDesktopMode by remember { mutableStateOf(settings.musicDesktopMode) }
    var showBack by remember { mutableStateOf(settings.showBack) }
    var delayTvEpisodes by remember { mutableStateOf(settings.delayTvEpisodes) }
    var showRestore by remember { mutableStateOf(settings.showRestore) }
    var showPlayerDiagnostics by remember { mutableStateOf(settings.showPlayerDiagnostics) }
    // Match frame rate by default on televisions; phones remain disabled.
    // Explicit prior choices persist. Both matching options default on for TV.
    var matchTvFrameRate by remember { mutableStateOf(settings.matchTvFrameRate) }
    var iptvBuffer by remember { mutableStateOf(settings.iptvBuffer) }
    var matchTvResolution by remember { mutableStateOf(settings.matchTvResolution) }
    var matchStreamQuality by remember { mutableStateOf(settings.matchStreamQuality) }
    var adBlockerMode by remember { mutableStateOf(settings.adBlockerMode) }
    val blockerStatus by JaGeckoAdBlockers.status.collectAsState()
    var blockerTools by remember { mutableStateOf<JaGeckoAdBlockers.ManagementPages?>(null) }
    var blockerToolsError by remember { mutableStateOf<String?>(null) }
    var confirmPreferredSourceReset by remember { mutableStateOf(false) }
    var preferredSourceResetStatus by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf("Movies") }
    var settingsSection by remember { mutableStateOf<String?>(null) }
    var settingsReturnSection by remember { mutableStateOf<String?>(null) }
    val settingsGroups = remember { listOf("Account", "Ad Blocking", "Player", "Tabs", "Sources") }
    val settingsGroupFocus = remember { settingsGroups.associateWith { FocusRequester() } }
    val settingsBackFocus = remember { FocusRequester() }
    val mainTabs = remember(showIptvTab, showMusicTab) { jaMainTabNames(showIptvTab, showMusicTab) }
    LaunchedEffect(showMusicTab, showIptvTab) { if ((!showMusicTab && tab == "Music") || (!showIptvTab && tab == "IPTV")) tab = "Movies" }
    LaunchedEffect(settingsSection) {
        withFrameNanos { }
        if (settingsSection == null) {
            settingsReturnSection?.let { section -> runCatching { settingsGroupFocus[section]?.requestFocus() } }
            settingsReturnSection = null
        } else runCatching { settingsBackFocus.requestFocus() }
    }
    // Keep independent vertical and horizontal scroll positions for Movies and TV.
    val sectionScrollStates = remember { mutableMapOf<String, LazyListState>() }
    val genreScrollStates = remember { mutableMapOf<String, LazyListState>() }
    val genreJumpStates = remember { mutableMapOf<String, JaRowJumpState>() }
    val continueMovieGridState = rememberLazyGridState()
    val continueTvGridState = rememberLazyGridState()
    var genreRows by remember { mutableStateOf(emptyList<GenreRow>()) }
    var genresLoading by remember { mutableStateOf(false) }
    val genreRowLoading = remember { mutableStateMapOf<String,Boolean>() }
    val genreRowErrors = remember { mutableStateMapOf<String,String>() }
    // Movies can switch between the existing genre rows and a paged popular grid.
    var moviesView by remember { mutableStateOf("Popular") }
    val popularGridState = rememberLazyGridState()
    var popularTitles by remember(catalogueDate, catalogueGeneration, sourceDataVersion) { mutableStateOf(emptyList<TitleItem>()) }
    var popularPage by remember(catalogueDate, catalogueGeneration, sourceDataVersion) { mutableIntStateOf(0) }
    var popularTotalPages by remember(catalogueDate, catalogueGeneration, sourceDataVersion) { mutableIntStateOf(1) }
    var popularLoading by remember(catalogueDate, catalogueGeneration, sourceDataVersion) { mutableStateOf(false) }
    var popularError by remember(catalogueDate, catalogueGeneration, sourceDataVersion) { mutableStateOf<String?>(null) }
    val otherGridState = key(catalogueDate, catalogueGeneration, sourceDataVersion) { rememberLazyGridState() }
    var otherTitles by remember(catalogueDate, catalogueGeneration, sourceDataVersion) { mutableStateOf(emptyList<TitleItem>()) }
    var otherPage by remember(catalogueDate, catalogueGeneration, sourceDataVersion) { mutableIntStateOf(0) }
    var otherTotalPages by remember(catalogueDate, catalogueGeneration, sourceDataVersion) { mutableIntStateOf(1) }
    var otherLoading by remember(catalogueDate, catalogueGeneration, sourceDataVersion) { mutableStateOf(false) }
    var otherError by remember(catalogueDate, catalogueGeneration, sourceDataVersion) { mutableStateOf<String?>(null) }
    val otherDisplayTitles = remember(otherTitles,vidSrcQuality.cams) {
        (vidSrcQuality.cams.values.sortedBy { it.title } + otherTitles).distinctBy { it.id }
    }
    val otherMovieIds = remember(otherDisplayTitles) { otherDisplayTitles.map { it.id }.toSet() }
    val outsideOther = remember(otherMovieIds,vidSrcSnapshot,sourceDataVersion) { { titles:List<TitleItem> -> titles.filter {
        JaMovieReleaseRules.includeOutsideOther(it.type,it.id,otherMovieIds) && (it.type!="movie"||JaVidSrcCatalog.includeMovie(it.id))
    } } }
    // TV has its own Popular/Genres toggle, paged results and scroll state.
    // Keep the original TV genre rows and all Movies/Search state intact.
    var tvView by remember { mutableStateOf("Popular") }
    val networksRepo = remember(catalogueDate, catalogueGeneration) { TmdbNetworksRepository(appContext) }
    val networksState = remember(networksRepo) { JaNetworksBrowseState() }
    val tvPopularGridState = rememberLazyGridState()
    var tvPopularTitles by remember { mutableStateOf(emptyList<TitleItem>()) }
    var tvPopularPage by remember { mutableIntStateOf(0) }
    var tvPopularTotalPages by remember { mutableIntStateOf(1) }
    var tvPopularLoading by remember { mutableStateOf(false) }
    var tvPopularError by remember { mutableStateOf<String?>(null) }
    var moviePopularStartupResetDone by remember { mutableStateOf(false) }
    var tvPopularStartupResetDone by remember { mutableStateOf(false) }
    // Favorites are one existing database collection, presented by media type.
    // Remember each subsection's scroll offset while browsing title details.
    val favoriteMovieGridState = rememberLazyGridState()
    val favoriteTvGridState = rememberLazyGridState()
    var movieFavoriteIds by remember { mutableStateOf(emptyList<Int>()) }
    var tvFavoriteIds by remember { mutableStateOf(emptyList<Int>()) }
    var results by remember { mutableStateOf(emptyList<TitleItem>()) }
    var selected by remember { mutableStateOf<TitleItem?>(null) }
    var selectedPerson by remember { mutableStateOf<TmdbPersonCredit?>(null) }
    val detailsBackStack = remember { mutableStateListOf<DetailReturnPoint>() }
    var playback by remember { mutableStateOf<PlaybackSource?>(null) }
    val liveTv = remember { JaLiveTvState(appContext, JaLiveTvRepository(appContext), scope) }
    val liveActive = tab == "IPTV" && (liveTv.playerAttached || liveTv.previewChannel?.id==JA_WEATHER_CHANNEL)
    LaunchedEffect(tab){if(tab != "IPTV"){liveTv.fullscreen=false;liveTv.finishMove(false)}}
    val weather = rememberJaWeather(active = playback == null && (!liveTv.fullscreen || liveTv.previewChannel?.id==JA_WEATHER_CHANNEL) && !exiting)
    val navigationRowState = androidx.compose.foundation.lazy.rememberLazyListState()
    val mainRequesters = remember { listOf("Movies","TV","IPTV","Music","Search","Settings").associateWith { FocusRequester() } }
    val subRequester = remember { FocusRequester() }
    var navigationLevel by remember { mutableIntStateOf(0) }
    var initialNavigationFocused by remember { mutableStateOf(false) }
    if (openMusicRequest == 0) ReportDrawnWhen { initialNavigationFocused }
    fun focusMain() { exitGate.reset(); scope.launch {
        val index = mainTabs.indexOf(tab)
        navigationRowState.animateScrollToItem(index.coerceAtLeast(0)); withFrameNanos { }
        runCatching { mainRequesters[tab]?.requestFocus() }
    } }
    SideEffect {
        weather.returnToNavigation = {
            scope.launch {
                navigationRowState.scrollToItem(mainTabs.indexOf("Settings").coerceAtLeast(0))
                withFrameNanos { }; withFrameNanos { }
                weather.navigationFocus.requestFocus()
            }
        }
    }
    var weatherOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var weatherReturnFocus by remember { mutableStateOf(false) }
    LaunchedEffect(weatherOpen, weatherReturnFocus) {
        if (!weatherOpen && weatherReturnFocus) {
            withFrameNanos { }
            runCatching { weather.cardFocus.requestFocus() }
            weatherReturnFocus = false
        }
    }
    var request by remember { mutableStateOf<PlaybackRequest?>(null) }
    // Ordered media candidates for the current title; never switch on a mere delay.
    var playbackChoices by remember { mutableStateOf(emptyList<PlaybackChoice>()) }
    var playbackChoiceIndex by remember { mutableIntStateOf(0) }
    // A session generation ensures fallback gets fresh GeckoView and display matching.
    var playbackSessionId by remember { mutableIntStateOf(0) }
    LaunchedEffect(openMusicRequest) {
        if (openMusicRequest > 0) {
            selected = null; selectedPerson = null; playback = null
            detailsBackStack.clear(); tab = if (showMusicTab) "Music" else "Settings"
        }
    }
    var history by remember { mutableStateOf(emptyList<WatchItem>()) }
    // Search state lives above the details routes so Back restores the query and results.
    var search by remember { mutableStateOf("") }
    var searchMode by remember { mutableStateOf(JaSearchMode.ALL) }
    val searchHistoryStore = remember { JaSearchHistory(appContext) }
    var recentSearches by remember { mutableStateOf(searchHistoryStore.all()) }
    val searchGridState = rememberLazyGridState() // Keep search position while viewing title/person details.
    // A new activity starts at the beginning. Returning from details keeps in-session positions.
    LaunchedEffect(Unit) {
        navigationRowState.scrollToItem(0);titleReturnFocus.clear()
        if(openMusicRequest==0){weatherOpen=false;weatherReturnFocus=false}
        withFrameNanos{};withFrameNanos{};if(openMusicRequest==0)runCatching{mainRequesters.getValue("Movies").requestFocus()}
        withFrameNanos {}
        listOf(continueMovieGridState,continueTvGridState,popularGridState,otherGridState,tvPopularGridState,
            favoriteMovieGridState,favoriteTvGridState,searchGridState).forEach{it.scrollToItem(0)}
        sectionScrollStates.values.forEach{it.scrollToItem(0)};genreScrollStates.values.forEach{it.scrollToItem(0)}
        networksState.listState.scrollToItem(0);networksState.rowStates.values.forEach{it.scrollToItem(0)}
        liveTv.list.scrollToItem(0);liveTv.groupList.scrollToItem(0);liveTv.timeline.scrollTo(0)
        Log.i("JA_STARTUP","LISTS_RESET main=Movies index=0")
    }
    // Lazy grids can restore a saved index before async Popular results arrive.
    // Reset once after each list first becomes available on this activity startup.
    LaunchedEffect(popularTitles.isNotEmpty()) {
        if (popularTitles.isNotEmpty() && !moviePopularStartupResetDone) {
            withFrameNanos { }
            popularGridState.scrollToItem(0)
            moviePopularStartupResetDone = true
        }
    }
    LaunchedEffect(tvPopularTitles.isNotEmpty()) {
        if (tvPopularTitles.isNotEmpty() && !tvPopularStartupResetDone) {
            withFrameNanos { }
            tvPopularGridState.scrollToItem(0)
            tvPopularStartupResetDone = true
        }
    }
    fun focusSub() { exitGate.reset(); scope.launch {
        if(tab=="Search")searchGridState.scrollToItem(0)
        withFrameNanos { };withFrameNanos { };runCatching { subRequester.requestFocus() }
    } }
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
    var searchGenreSources by remember { mutableStateOf(sourceDataVersion) }
    var searchGenreDataGeneration by remember { mutableLongStateOf(-1L) }
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
    var episodesLoading by remember { mutableStateOf(false) }
    var episodesError by remember { mutableStateOf<String?>(null) }
    var episodeReload by remember { mutableIntStateOf(0) }
    val visibleEpisodes = remember(episodes, delayTvEpisodes, catalogueDate) {
        episodes.filter { JaEpisodeReleaseRules.visible(it.airDate, catalogueDate, delayTvEpisodes) }
    }
    // Reload metadata on a new local day or foreground refresh. Keep unfiltered
    // dates in the cache so disabling the setting restores the full list.
    LaunchedEffect(selected?.id, selected?.type, season, seasons, catalogueDate, catalogueGeneration, episodeReload) {
        val title = selected
        if (title?.type != "tv" || season !in seasons) return@LaunchedEffect
        episodesLoading = true
        episodesError = null
        episodes = emptyList()
        try { episodes = repo.episodes(title.id, season) }
        catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (problem: Exception) { episodesError = "Episodes unavailable. Select the season to retry." }
        finally { episodesLoading = false }
    }
    var error by remember { mutableStateOf<String?>(null) }
    var playbackLoading by remember { mutableStateOf(false) }
    val historyRefreshJob = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
    val historyRefreshPending = remember { booleanArrayOf(false) }
    fun refreshHistory() {
        historyRefreshPending[0] = true
        if (historyRefreshJob[0]?.isActive == true) return
        historyRefreshJob[0] = scope.launch {
            do {
            historyRefreshPending[0] = false
            val (current, order) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val current = dao.all()
                current to (favoritesOrder.reconcile("movie", current) to favoritesOrder.reconcile("tv", current))
            }
            movieFavoriteIds = order.first
            tvFavoriteIds = order.second
            history = current
            } while (historyRefreshPending[0])
        }
    }
    LaunchedEffect(cloudApplyGeneration) {
        if (cloudApplyGeneration > 0) {
            refreshHistory()
            liveTv.reloadPersonalFavorites()
            JaMusicLibrary.notifyCloudRestore()
            recentSearches = searchHistoryStore.all()
            showMusicTab = settings.showMusicTab
            musicDesktopMode = settings.musicDesktopMode
            showBack = settings.showBack
            delayTvEpisodes = settings.delayTvEpisodes
            showRestore = settings.showRestore
            showPlayerDiagnostics = settings.showPlayerDiagnostics
            matchTvFrameRate = settings.matchTvFrameRate
            matchTvResolution = settings.matchTvResolution
            matchStreamQuality = settings.matchStreamQuality
            adBlockerMode = settings.adBlockerMode
            weather.reloadPreferences()
        }
    }
    fun runSearch(text: String, desiredMode: JaSearchMode) {
        if (text.isNotBlank()) recentSearches = searchHistoryStore.record(text, desiredMode)
        titleReturnFocus.clear()
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
    fun browseGenre(genre: JaSearchGenre, refreshing: Boolean = false) {
        searchGenreSources = sourceDataVersion
        searchGenreDataGeneration = catalogueGeneration
        val requestGeneration = catalogueGeneration
        val requestSources = sourceDataVersion
        genreSearchRevision += 1
        val revision = genreSearchRevision
        if (!refreshing) {
            genreDirectoryBookmark = searchGridState.firstVisibleItemIndex to searchGridState.firstVisibleItemScrollOffset
            scope.launch { searchGridState.scrollToItem(0) }
        }
        searchGenre = genre
        Log.i("JA_SEARCH", "GENRE_OPEN id=${genre.id} type=${genre.type} name=${genre.name}")
        searchGenreTitles = emptyList()
        searchGenrePage = 0
        searchGenreTotalPages = 1
        searchGenreLoadingMore = false
        searchGenrePageError = null
        searchError = null
        searchGenreLoading = true
        if (genre.type == "movie" && !vidSrcSnapshot.ready) { searchGenreLoading = false; return }
        scope.launch {
            try {
                val found = searchRepo.genreTitlesPage(genre, 1)
                if (genreSearchRevision == revision && catalogueGeneration == requestGeneration && (genre.type != "movie" || sourcesCurrent(requestSources))) {
                    searchGenreTitles = found.titles
                    searchGenrePage = found.page
                    searchGenreTotalPages = found.totalPages
                    Log.i("JA_SEARCH", "GENRE_PAGE id=${genre.id} page=${found.page} count=${found.titles.size}")
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (ex: Exception) { if (genreSearchRevision == revision && catalogueGeneration == requestGeneration && (genre.type != "movie" || sourcesCurrent(requestSources))) searchError = ex.message }
            finally { if (genreSearchRevision == revision && catalogueGeneration == requestGeneration && (genre.type != "movie" || sourcesCurrent(requestSources))) searchGenreLoading = false }
        }
    }
    fun loadMoreGenre() {
        val genre = searchGenre ?: return
        if (genre.type == "movie" && !vidSrcSnapshot.ready) return
        if (searchGenreLoading || searchGenreLoadingMore || searchGenrePage == 0 ||
            searchGenrePage >= searchGenreTotalPages || searchGenrePage >= 500) return
        val revision = genreSearchRevision
        val requestGeneration = catalogueGeneration
        val requestSources = sourceDataVersion
        val nextPage = searchGenrePage + 1
        searchGenreLoadingMore = true
        searchGenrePageError = null
        scope.launch {
            try {
                val found = searchRepo.genreTitlesPage(genre, nextPage)
                if (genreSearchRevision == revision && catalogueGeneration == requestGeneration && (genre.type != "movie" || sourcesCurrent(requestSources)) && searchGenre == genre) {
                    searchGenreTitles = (searchGenreTitles + found.titles).distinctBy { it.type to it.id }
                    searchGenrePage = found.page
                    searchGenreTotalPages = found.totalPages
                    Log.i("JA_SEARCH", "GENRE_MORE id=${genre.id} page=${found.page} total=${searchGenreTitles.size}")
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (ex: Exception) {
                if (genreSearchRevision == revision && catalogueGeneration == requestGeneration && (genre.type != "movie" || sourcesCurrent(requestSources))) searchGenrePageError = "Couldn't load more titles: ${ex.message}"
            } finally {
                if (genreSearchRevision == revision && catalogueGeneration == requestGeneration && (genre.type != "movie" || sourcesCurrent(requestSources))) searchGenreLoadingMore = false
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
    fun loadMoreGenreRow(type: String, row: GenreRow) {
        val key="$type:${row.genreId}"
        if(genresLoading || genreRowLoading[key]==true || !row.hasMore)return
        val requestSources=sourceDataVersion
        val requestDate=catalogueDate
        val requestGeneration=catalogueGeneration
        val genreRepo=if(type=="movie")movieCatalogueRepo else repo
        genreRowLoading[key]=true;genreRowErrors.remove(key)
        scope.launch {
            try {
                val next=genreRepo.moreGenre(type,row)
                if(java.time.LocalDate.now()!=requestDate || catalogueGeneration!=requestGeneration || !sourcesCurrent(requestSources))return@launch
                val currentType=if(tab=="Movies")"movie" else if(tab=="TV")"tv" else ""
                val currentView=if(tab=="Movies")moviesView else tvView
                if(currentType==type && currentView=="Genres") {
                    genreRows=genreRows.map { if(it.genreId==row.genreId)next else it }
                    val previousIds=row.titles.map { it.id }.toSet()
                    val visible=outsideOther(next.titles)
                    val firstNew=visible.indexOfFirst { it.id !in previousIds }
                    if(firstNew>=0) {
                        genreScrollStates["$tab:${row.name}"]?.scrollToItem(firstNew)
                        val title=visible[firstNew]
                        titleReturnFocus.focus("$tab:Genres:${row.name}:${title.type}:${title.id}")
                    }
                }
            }catch(cancel:kotlinx.coroutines.CancellationException){throw cancel}
            catch(ex:Exception){genreRowErrors[key]="Couldn't load this genre. Select Retry."}
            finally{genreRowLoading[key]=false}
        }
    }
    fun loadMorePopular() {
        if (!vidSrcSnapshot.ready) return
        if (popularLoading || popularPage >= popularTotalPages || popularPage >= 500) return
        val requestSources = sourceDataVersion
        val requestDate = catalogueDate
        val requestGeneration = catalogueGeneration
        popularLoading = true
        popularError = null
        var fetchPage=popularPage+1
        scope.launch {
            try {
                val batch=JaCatalogBatch(popularPage,popularTotalPages,5)
                while(batch.hasNext()) {
                    fetchPage=batch.nextPage
                    val found = searchRepo.popularMoviesPage(fetchPage)
                    if (java.time.LocalDate.now() != requestDate || catalogueGeneration != requestGeneration || !sourcesCurrent(requestSources)) return@launch
                    batch.accept(found.page,found.totalPages)
                    popularTitles = (popularTitles + found.titles).distinctBy { it.type to it.id }
                    popularPage = batch.lastPage
                    popularTotalPages = batch.totalPages
                    found.titles.forEach { JaVidSrcCatalog.observe(it) }
                    Log.i("JA_MOVIES", "POPULAR_PAGE page=${found.page} total=${popularTitles.size} batchPages=5")
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (ex: Exception) {
                if (java.time.LocalDate.now() == requestDate && catalogueGeneration == requestGeneration && sourcesCurrent(requestSources)) popularError = "Couldn't load popular movies: ${ex.message}"
                Log.w("JA_MOVIES", "POPULAR_LOAD_FAILED page=$fetchPage", ex)
            } finally { if (java.time.LocalDate.now() == requestDate && catalogueGeneration == requestGeneration && sourcesCurrent(requestSources)) popularLoading = false }
        }
    }
    fun loadMoreOther() {
        if (otherLoading || otherPage >= otherTotalPages || otherPage >= 500) return
        val nextPage = otherPage + 1
        val requestSources = sourceDataVersion
        val requestDate = catalogueDate
        val requestGeneration = catalogueGeneration
        otherLoading = true; otherError = null
        scope.launch {
            try {
                val found = searchRepo.otherMoviesPage(nextPage)
                if (java.time.LocalDate.now() != requestDate || catalogueGeneration != requestGeneration || !sourcesCurrent(requestSources)) return@launch
                otherTitles = (otherTitles + found.titles).distinctBy { it.id }
                otherPage = found.page
                // A filtered batch may be empty while later source pages still match.
                otherTotalPages = found.totalPages
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (ex: Exception) { if (java.time.LocalDate.now() == requestDate && catalogueGeneration == requestGeneration && sourcesCurrent(requestSources)) otherError = "Couldn't load Other movies: ${ex.message}" }
            finally { if (java.time.LocalDate.now() == requestDate && catalogueGeneration == requestGeneration && sourcesCurrent(requestSources)) otherLoading = false }
        }
    }
    fun loadMorePopularTV() {
        if (tvPopularLoading || tvPopularPage >= tvPopularTotalPages || tvPopularPage >= 500) return
        tvPopularLoading = true
        tvPopularError = null
        var fetchPage = tvPopularPage + 1
        scope.launch {
            try {
                val batch = JaCatalogBatch(tvPopularPage,tvPopularTotalPages,5)
                while (batch.hasNext()) {
                    fetchPage = batch.nextPage
                    val found = searchRepo.popularShowsPage(fetchPage)
                    val totalPages = if (found.titles.isEmpty()) found.page else found.totalPages
                    batch.accept(found.page,totalPages)
                    tvPopularTitles = (tvPopularTitles + found.titles).distinctBy { it.type to it.id }
                    tvPopularPage = batch.lastPage
                    tvPopularTotalPages = batch.totalPages
                    Log.i("JA_TV", "POPULAR_PAGE page=${found.page} total=${tvPopularTitles.size} batchPages=5")
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (ex: Exception) {
                tvPopularError = "Couldn't load popular TV shows: ${ex.message}"
                Log.w("JA_TV", "POPULAR_LOAD_FAILED page=$fetchPage", ex)
            } finally { tvPopularLoading = false }
        }
    }
    val musicPlayback = remember {
        JaMusicLabService.state.map { it.running && it.queue.isNotEmpty() }.distinctUntilChanged()
    }
    val musicActive by musicPlayback.collectAsState(initial = false)
    val playbackBusy = playback != null || playbackLoading || liveActive || musicActive || weatherOpen
    val idleCheckChannels = liveTv.channels
    val idleChecksAllowed = !playbackBusy && !exiting && (tab != "IPTV" || liveTv.previewChannel == null)
    LaunchedEffect(lifecycleOwner, idleChecksAllowed, idleCheckChannels) {
        if (idleChecksAllowed && idleCheckChannels.any { it.urls.isNotEmpty() }) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                delay(15_000)
                while (isActive) {
                    liveTv.repository.checkIdleSources(idleCheckChannels)
                    delay(45_000)
                }
            }
        }
    }
    LaunchedEffect(playbackBusy, catalogueGeneration, catalogueDate) {
        JaVidSrcCatalog.playbackBusy(playbackBusy)
        if (!playbackBusy) {
            delay(1500)
            JaVidSrcCatalog.refreshOverdue()
        }
    }
    LaunchedEffect(playback,playbackLoading,liveActive,weatherOpen,weather.sound) {
        JaMusicService.setVideoActive(playback != null || playbackLoading || liveActive || (weatherOpen&&weather.weatherStar&&weather.sound))
    }
    val catalogueVisible = !liveTv.fullscreen && selected == null && selectedPerson == null && playback == null && !playbackLoading && !exiting
    // Invalidate promptly, but defer catalogue requests until playback/details are closed.
    LaunchedEffect(catalogueGeneration, catalogueDate, catalogueVisible, tab, searchGenre, sourceDataVersion) {
        val genre = searchGenre
        if (catalogueVisible && tab == "Search" && genre != null &&
            (searchGenreDataGeneration != catalogueGeneration || (genre.type == "movie" && searchGenreSources != sourceDataVersion)) &&
            (genre.type != "movie" || vidSrcSnapshot.ready)) browseGenre(genre, refreshing = true)
    }
    LaunchedEffect(tab, if (tab == "Movies") moviesView else tvView, catalogueDate, catalogueGeneration, catalogueVisible, sourceDataVersion) {
        if (!catalogueVisible) return@LaunchedEffect
        when (tab) {
            "Movies" -> if (moviesView == "Popular" && popularPage == 0 && !popularLoading) {
                loadMorePopular()
            } else if (moviesView == "Other" && otherPage == 0 && !otherLoading) {
                loadMoreOther()
            }
            "TV" -> if (tvView == "Popular" && tvPopularPage == 0 && !tvPopularLoading) {
                loadMorePopularTV()
            }
        }
    }
    // Fetch the existing genre rows only when the corresponding Genres view is selected.
    // Popular switching keeps the previously fetched pages and individual grid scroll positions.
    LaunchedEffect(tab, if (tab == "Movies") moviesView else tvView, catalogueDate, catalogueGeneration, catalogueVisible, sourceDataVersion) {
        if (!catalogueVisible) return@LaunchedEffect
        if (tab != "IPTV") refreshHistory()
        when (tab) {
            "Movies", "TV" -> {
                val activeView = if (tab == "Movies") moviesView else tvView
                if (activeView == "Genres") {
                    results = emptyList()
                    val genreType = if (tab == "Movies") "movie" else "tv"
                    if (genreType == "movie" && !vidSrcSnapshot.ready) {
                        genreRows = emptyList(); genresLoading = true; return@LaunchedEffect
                    }
                    val genreRepo = if (genreType == "movie") movieCatalogueRepo else repo
                    genreRows = genreRepo.cachedGenreRows(genreType).orEmpty()
                    genresLoading = genreRows.isEmpty()
                    error = null
                    try { genreRows = genreRepo.genreRows(genreType) { partial ->
                        genreRows = partial
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
            "Continue" -> Unit // Derive cards directly from current history below.
        }
    }
    // DETAILS_NAV v1.1.4b: retain every title/person route so Back returns to its caller.
    LaunchedEffect(Unit) { Log.i("JA_DETAILS_NAV", "v1.1.4b navigation patch active") }
    fun showTitle(item: TitleItem) {
        pendingCastFocus = null
        pendingEpisodeFocus = null
        pendingPlayFocus = true
        titleScrollBookmarks.remove("${item.type}:${item.id}")
        selected = item; episodes = emptyList(); seasons = emptyList()
        episodesLoading = item.type == "tv"; episodesError = null
        JaVidSrcCatalog.observe(item)
        // Details load TMDB metadata only; no provider HTTP or hidden-video tests.
        Log.i("JA_SERVERS", "DETAILS_NO_PROBES type=${item.type} tmdb=${item.id}")
        if (item.type == "tv") scope.launch {
            try {
                val loadedSeasons = repo.seasons(item.id)
                val selectedSeason = history.firstOrNull { it.mediaId == item.id && it.mediaType == "tv" &&
                    it.episode > 0 && it.season in loadedSeasons }?.season ?: loadedSeasons.firstOrNull() ?: 1
                // Ignore an old response if the user has already navigated away.
                if (selected?.id == item.id && selected?.type == item.type) {
                    seasons = loadedSeasons; season = selectedSeason
                    if (loadedSeasons.isEmpty()) episodesLoading = false
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (e: Exception) { if (selected?.id == item.id) {
                error = e.message; episodesError = "Seasons unavailable. Reopen this title to retry."; episodesLoading = false
            } }
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
        pendingEpisodeFocus = null
        pendingPlayFocus = false
        pendingCastFocus = null
        if (tab == "Search" && selected == null && selectedPerson == null) {
            searchReturnBookmark = searchGridState.firstVisibleItemIndex to searchGridState.firstVisibleItemScrollOffset
        }
        // From title details, remember the title. From Search, return straight to Search.
        if (selected != null || selectedPerson != null) {
            detailsBackStack.add(DetailReturnPoint(selected, selectedPerson,
                castId = if (selectedPerson == null && selected != null) person.id else null))
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
            if (previous.person == null && previous.title != null && previous.castId != null) {
                pendingPlayFocus = false
                pendingCastFocus = "${previous.title.type}:${previous.title.id}" to previous.castId
            }
            Log.i("JA_DETAILS_NAV", "BACK_TO_${if (previous.person == null) "TITLE" else "PERSON"} id=${previous.title?.id ?: 0} depth=${detailsBackStack.size}")
        } else {
            selectedPerson = null
            selected = null
            titleReturnFocus.returned()
            Log.i("JA_DETAILS_NAV", "BACK_TO_CATALOG depth=0")
        }
        if (tab == "Search" && selected == null && selectedPerson == null) {
            pendingSearchRestore = searchReturnBookmark
        }
    }
    var resumePrompt by remember { mutableStateOf<PlaybackRequest?>(null) }
    var resumePositionMs by remember { mutableStateOf(0L) }
    var restartFromBeginning by remember { mutableStateOf(false) }
    var resumeLookupBusy by remember { mutableStateOf(false) }
    fun episodeAllowed(item: TitleItem, s: Int, e: Int): Boolean {
        if (item.type != "tv" || !delayTvEpisodes) return true
        val ep = if (selected?.id == item.id && selected?.type == "tv")
            episodes.firstOrNull { it.season == s && it.episode == e } else null
        val allowed = ep != null && JaEpisodeReleaseRules.visible(ep.airDate, java.time.LocalDate.now(), true)
        if (!allowed) error = "This episode is not available under the release-date filter."
        return allowed
    }
    fun startPlayback(item: TitleItem, s: Int = 0, e: Int = 0, startAtMs: Long = 0L, restart: Boolean = false) {
        if (!episodeAllowed(item, s, e)) return
        JaMusicService.setVideoActive(true)
        // Direct playback: no HTTP preflight, no detached verification, and no
        // HTTP ranking. TV seasons can reuse a locally learned working source.
        playbackEpisodeTarget = if(item.type=="tv")s to e else null
        pendingEpisodeFocus = null
        pendingPlayFocus = false
        resumePositionMs = startAtMs
        restartFromBeginning = restart
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
                // Movies keep the default allowlist order. A TV episode may
                // promote only the working provider learned for its own season.
                // This is a local preference lookup, never an HTTP preflight.
                val choices = preferredSourceReset.rankTvSeason(r, candidates)
                Log.i("JA_SERVERS", "SOURCE_START_ORDER " +
                    "type=${r.media.type} tmdb=${r.media.id} season=${r.season} " +
                    "order=${choices.joinToString(",") { it.id }} " +
                    "preference=tv-season-only")
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
        if (!episodeAllowed(item, s, e)) return
        if (resumeLookupBusy) return
        resumeLookupBusy = true
        scope.launch {
            try {
                val saved = dao.find(item.id, item.type, s, e)
                if (saved != null && saved.positionMs >= 5_000L && WatchedStore.key(item.id, item.type, s, e) !in watched) {
                    resumePositionMs = saved.positionMs
                    resumePrompt = PlaybackRequest(item, s, e)
                } else startPlayback(item, s, e)
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (problem: Exception) { error = "Could not load saved progress. Please try again." }
            finally { resumeLookupBusy = false }
        }
    }
    resumePrompt?.let { pending ->
        JaCompactContent {
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
                        startPlayback(pending.media, pending.season, pending.episode, 0L, restart = true)
                    } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                    catch (problem: Exception) { error = "Could not reset saved progress. Please try again." }
                }
            }) { Text("Start over") } }
        )
        }
    }
    fun nextPlaybackSource(reason: String, recordFailure: Boolean = true) {
        // No HTTP checks here either. All enabled sources are known from the
        // registry; actual GeckoView failures decide when to try the next one.
        val failed = playbackChoices.getOrNull(playbackChoiceIndex) ?: return
        if (recordFailure) request?.let { current ->
            serverPlaybackHistory.recordExplicitFailure(current, failed.id, reason)
            preferredSourceReset.recordTvSeasonFailure(current, failed.id)
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
    fun closePlayback() {
        playback = null
        pendingPlayFocus = playbackReturnToPlay
        pendingEpisodeFocus = playbackEpisodeTarget
        refreshHistory()
    }
    val weatherHeader = remember(tab, moviesView, tvView, selected?.id, selectedPerson?.id) { JaWeatherHeaderState() }
    LaunchedEffect(tab, selected?.id, selectedPerson?.id, playback, searchGenre, blockerTools) {
        exitGate.reset()
    }
    BackHandler {
        if (exiting) return@BackHandler
        when {
            tab == "Settings" && settingsSection != null -> { settingsReturnSection = settingsSection; settingsSection = null }
            tab == "IPTV" && liveTv.fullscreen -> { liveTv.fullscreen = false }
            tab == "IPTV" -> { tab = "Movies"; navigationLevel = 0; focusMain() }
            weatherOpen -> { weatherOpen = false; weatherReturnFocus = true }
            playback != null -> {
                Log.i("JA_SOURCES", "PLAYER_CLOSED reason=ANDROID_BACK")
                closePlayback()
            }
            selected != null || selectedPerson != null -> backFromDetails()
            tab == "Music" && JaMusicService.back() -> exitGate.reset()
            tab == "Search" && searchGenre != null -> exitGenre()
            (tab == "Movies" || tab == "TV") && navigationLevel > 0 -> {
                if (JaNavigationRules.backTarget(navigationLevel) == 1) focusSub() else focusMain()
            }
            else -> {
                if (exitGate.press(android.os.SystemClock.elapsedRealtime())) {
                    exiting = true
                    liveTv.stopPlayback()
                    JaMusicLabService.stop(appContext)
                    JaMusicService.stop(appContext)
                    scope.launch {
                        try {
                            kotlinx.coroutines.withTimeoutOrNull(2_000L) { PlaybackProgress.flush() }
                        } finally {
                            activity?.closeFromDoubleBack()
                        }
                    }
                } else android.widget.Toast.makeText(appContext,
                    "Press Back again to exit", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    if (exiting) {
        Box(Modifier.fillMaxSize().background(bg), contentAlignment = Alignment.Center) {
            Text("Closing JA…", color = Color.White)
        }
        return
    }
    if (weatherOpen) {
        JaWeatherPage(weather, onClose = { weatherOpen = false; weatherReturnFocus = true },
            onSettings = { weatherOpen = false; tab = "Settings"; settingsSection = "Tabs"; selected = null; selectedPerson = null; detailsBackStack.clear() },
            onWeatherChannel = { weatherOpen=false;tab="IPTV";selected=null;selectedPerson=null;detailsBackStack.clear();liveTv.tune(jaWeatherChannel(),true) })
        return
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
            restartFromBeginning = restartFromBeginning,
            onRetry = { playbackSessionId += 1 },
            onDisableBlockerAndRetry = {
                adBlockerMode = JaGeckoAdBlockers.OFF
                settings.adBlockerMode = JaGeckoAdBlockers.OFF
                playbackSessionId += 1
            },
            onPositionChanged = { resumePositionMs = it; restartFromBeginning = false },
            showBack = showBack, showRestore = showRestore,
            showDiagnostics = showPlayerDiagnostics,
            matchTvFrameRate = matchTvFrameRate,
            matchTvResolution = matchTvResolution,
            matchStreamQuality = matchStreamQuality,
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
                    // Learn the enabled provider actually used, not an alias host.
                    preferredSourceReset.recordTvSeasonStarted(activeRequest, current.id)
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
                    preferredSourceReset.recordTvSeasonFailure(it, playingSourceId)
                }
            },
            onClose = {
                Log.i("JA_SOURCES", "PLAYER_CLOSED reason=PLAYER_BACK_BUTTON")
                closePlayback()
            }
        )
        }
        return
    }
    if (showIptvTab && tab == "IPTV" && selected == null && selectedPerson == null) {
        JaLiveTvScreen(liveTv, weather,
            onMainMenu = { tab = "Movies"; navigationLevel = 0; focusMain() },
            matchTvFrameRate = matchTvFrameRate,
            matchTvResolution = matchTvResolution,
            matchStreamQuality = matchStreamQuality,
            modifier = Modifier.fillMaxSize())
        return
    }
    if (showMusicTab && tab == "Music" && selected == null && selectedPerson == null) {
        DisposableEffect(appContext) {
            onDispose {
                if (activity?.isChangingConfigurations != true) {
                    JaMusicLabService.stop(appContext)
                    JaMusicService.stop(appContext)
                }
            }
        }
        JaMusicLabScreen(Modifier.fillMaxSize(), onExit = { tab = "Movies"; navigationLevel = 0 })
        return
    }
    Box(Modifier.fillMaxSize().background(bg).windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)) {
    JaWeatherHeaderFrame(weatherHeader, weather, navigationRowState,
        onOpen = { exitGate.reset(); weatherOpen = true }) { sideWeather ->
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("JA", style = MaterialTheme.typography.headlineMedium, color = accent, modifier = Modifier.weight(1f))
            JaHeaderClock()
        }
        Spacer(Modifier.height(12.dp))
        LazyRow(state = navigationRowState, modifier = Modifier.fillMaxWidth()
            .onGloballyPositioned { weatherHeader.navigation = it.boundsInRoot() },
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            items(mainTabs, key = { it }, contentType = { "navigation-tab" }) { t ->
                TvFocusButton(
                    modifier = Modifier.focusRequester(mainRequesters.getValue(t))
                        .then(if(t=="IPTV") Modifier.focusRequester(liveTv.mainFocus) else if(t=="Settings") Modifier.focusRequester(weather.navigationFocus).onGloballyPositioned { weatherHeader.settings=it.boundsInRoot() } else Modifier)
                        .onFocusChanged {
                            if (it.isFocused) {
                                navigationLevel = 0
                                initialNavigationFocused = true
                            }
                        }
                        .onPreviewKeyEvent { event ->
                            if(event.key==Key.DirectionDown && selected==null && selectedPerson==null && (tab in listOf("Movies","TV","IPTV","Search"))) {
                                if(event.type==KeyEventType.KeyDown) { if(tab=="IPTV") liveTv.focusPreview() else focusSub() }; true
                            } else false
                        },
                    onClick = { titleReturnFocus.clear(); exitGate.reset(); if (t == "Settings") settingsSection = null; tab = t; selected = null; selectedPerson = null; detailsBackStack.clear(); playback = null },
                    colors = ButtonDefaults.buttonColors(containerColor = if (tab == t) accent else Color.DarkGray)
                ) { Text(t, color = Color.Black) }
            }
            if (!sideWeather) item(key = "weather") {
                JaWeatherCard(weather, Modifier.width(216.dp).height(48.dp)) { exitGate.reset(); weatherOpen = true }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (showMusicTab && tab == "Music" && selected == null && selectedPerson == null) {
            JaMusicLabScreen(Modifier.fillMaxWidth().weight(1f))
        } else JaCompactContent {
        if (playbackLoading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Opening selected source…") }
        error?.let { Text(it, modifier = Modifier.jaWeatherIntroWidth(), color = Color(0xFFFFA8A8)) }
        if (selected == null && selectedPerson == null && (tab == "Movies" || (tab == "Search" && searchGenre?.type == "movie"))) {
            if (vidSrcSnapshot.busy || (!vidSrcSnapshot.ready && dailyVidSrcDownloads)) Text(
                if (vidSrcSnapshot.ready) "Updating VidSrc catalogue…" else "Preparing VidSrc catalogue…",
                modifier = Modifier.jaWeatherIntroWidth(), color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
            vidSrcSnapshot.error?.let {
                Text(it, modifier = Modifier.jaWeatherIntroWidth(), color = Color(0xFFFFA8A8), style = MaterialTheme.typography.bodySmall)
                if (!vidSrcSnapshot.busy) TvSecondaryButton("Retry source refresh", false) { JaVidSrcCatalog.refreshOverdue(force = true) }
            }
        }
        if (tab == "Settings" && selected == null) {
            if (settingsSection == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(Modifier.widthIn(max = 620.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
                        Text("Settings", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                        settingsGroups.forEach { group ->
                            TvFocusButton(onClick = { settingsReturnSection = group; settingsSection = group },
                                modifier = Modifier.width(220.dp).height(40.dp).focusRequester(settingsGroupFocus.getValue(group)),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray, contentColor = Color.White)) {
                                Text(group, textAlign = TextAlign.Center, maxLines = 1)
                            }
                        }
                    }
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        Modifier.widthIn(max = 900.dp).fillMaxWidth().fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                    TvFocusButton(onClick = { settingsReturnSection = settingsSection; settingsSection = null },
                        modifier = Modifier.width(220.dp).height(40.dp).focusRequester(settingsBackFocus),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray, contentColor = Color.White)) {
                        Text("← Settings", textAlign = TextAlign.Center, maxLines = 1)
                    }
                    Text(settingsSection.orEmpty(), modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth(),
                        style = MaterialTheme.typography.headlineSmall, color = Color.White, textAlign = TextAlign.Center)
                    Column(Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (settingsSection) {
                            "Account" -> {
                                JaAccountSyncSettings(dao, onCloudDataApplied = {
                                    liveTv.reloadPersonalFavorites()
                                    refreshHistory()
                                    JaMusicLibrary.notifyCloudRestore()
                                    recentSearches = searchHistoryStore.all()
                                    weather.reloadPreferences()
                                }, onSignedOut = onSignedOut)
                                JaUpdateSettings()
                                Text("This product uses the TMDB API but is not endorsed or certified by TMDB.",
                                    color = Color.LightGray, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                            }
                            "Ad Blocking" -> {
                                Text("Embedded player ad blocking", style = MaterialTheme.typography.titleLarge, color = Color.White)
                                Text("Choose one Firefox ad blocker. GeckoView initializes it when JA opens, and downloads the Mozilla-signed add-on only if needed. For a white-screen test, select Off to disable both uBlock and AdGuard. Popup windows and JS prompts remain blocked. Restart playback after changing this setting.",
                                    color = Color.LightGray, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                                    listOf(JaGeckoAdBlockers.UBLOCK to "uBlock Origin", JaGeckoAdBlockers.ADGUARD to "AdGuard",
                                        JaGeckoAdBlockers.OFF to "Off").forEach { (mode, name) ->
                                        TvFocusButton(onClick = {
                                            adBlockerMode = mode; settings.adBlockerMode = mode
                                            prewarmPlayerAdBlocker(appContext, mode)
                                        }, colors = ButtonDefaults.buttonColors(containerColor = if (adBlockerMode == mode) accent else Color.DarkGray)) {
                                            Text(name, color = Color.Black)
                                        }
                                    }
                                }
                                Text("Status: ${blockerStatus.message}", color =
                                    if (blockerStatus.phase == JaGeckoAdBlockers.Phase.FAILED ||
                                        blockerStatus.phase == JaGeckoAdBlockers.Phase.WARNING) Color(0xFFFFC48A) else Color.LightGray,
                                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                                Text("Enabled means Gecko started the extension; it does not verify its filter engine. Switching to Off waits for both filtering extensions to be disabled.",
                                    color = Color.LightGray, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                                if (adBlockerMode == JaGeckoAdBlockers.UBLOCK) {
                                    TvFocusButton(onClick = {
                                        blockerToolsError = null
                                        JaGeckoAdBlockers.managementPages(appContext) { pages, problem ->
                                            blockerTools = pages; blockerToolsError = problem
                                        }
                                    }, enabled = blockerStatus.mode == JaGeckoAdBlockers.UBLOCK &&
                                        blockerStatus.phase != JaGeckoAdBlockers.Phase.STARTING) { Text("Open uBlock dashboard / logger") }
                                }
                                blockerToolsError?.let { Text(it, color = Color(0xFFFFC48A), textAlign = TextAlign.Center) }
                            }
                            "Player" -> {
                                Text("Native IPTV buffer size (ExoPlayer)", style = MaterialTheme.typography.titleLarge, color = Color.White)
                                Text("Applies the next time you open a native IPTV channel. Embedded GeckoView streams use the provider's own buffering because sites do not expose a universal buffer control.",
                                    color = Color.LightGray, textAlign = TextAlign.Center)
                                JaIptvBuffer.entries.forEach { option ->
                                    TvSettingsToggle(option.label, iptvBuffer == option) { selected ->
                                        if (selected) {
                                            iptvBuffer = option
                                            settings.iptvBuffer = option
                                        }
                                    }
                                }
                                Text("Player controls", style = MaterialTheme.typography.titleLarge, color = Color.White)
                                Text("Choose which controls appear over GeckoView. Android's system Back action still exits playback.",
                                    color = Color.LightGray, textAlign = TextAlign.Center)
                                TvSettingsToggle("Show Back button", showBack) { showBack = it; settings.showBack = it }
                                TvSettingsToggle("Show Restore Episode button", showRestore) { showRestore = it; settings.showRestore = it }
                                Text("Player diagnostics", style = MaterialTheme.typography.titleLarge, color = Color.White)
                                TvSettingsToggle("Show player diagnostics overlay", showPlayerDiagnostics) {
                                    showPlayerDiagnostics = it; settings.showPlayerDiagnostics = it
                                }
                                Text("Shows provider, loading, media and navigation diagnostics over GeckoView. The normal player controls are unchanged.",
                                    color = Color.LightGray, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                                Text("Android TV display performance", style = MaterialTheme.typography.titleLarge, color = Color.White)
                                TvSettingsToggle("Match TV frame rate during playback", matchTvFrameRate) {
                                    matchTvFrameRate = it; settings.matchTvFrameRate = it
                                }
                                Text("Enabled by default on Android TV; disabled on phones. JA measures advancing video frames in ExoPlayer and supported embedded players before requesting a compatible refresh rate. A mode change may briefly blank the screen. Your previous display preference is restored when playback closes.",
                                    color = Color.LightGray, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                                TvSettingsToggle("Match TV output resolution to video", matchTvResolution,
                                    enabled = matchTvFrameRate) { matchTvResolution = it; settings.matchTvResolution = it }
                                Text("A 1080-class video can request 1080p at its matching frame rate instead of keeping the TV's 4K output. This waits for stable frame reports in embedded players. Frame-rate matching takes priority. The prior display preference is restored on exit.",
                                    color = Color.LightGray, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                                TvSettingsToggle("Match stream quality to TV output (supported players)", matchStreamQuality,
                                    enabled = matchTvFrameRate && matchTvResolution) { matchStreamQuality = it; settings.matchStreamQuality = it }
                                Text("Uses the supported player quality API after the TV mode is applied, selecting the best available rendition at or below output resolution. Providers without that API keep their own adaptive quality. Restart playback after changing this setting.",
                                    color = Color.LightGray, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                            }
                            "Tabs" -> {
                                Text("Tabs and dashboard", style = MaterialTheme.typography.titleLarge, color = Color.White)
                                TvSettingsToggle("Show IPTV tab", showIptvTab) {
                                    showIptvTab = it; settings.showIptvTab = it
                                    if (!it && tab == "IPTV") tab = "Movies"
                                }
                                TvSettingsToggle("Show Music tab", showMusicTab) {
                                    showMusicTab = it; settings.showMusicTab = it
                                    Log.i("JA_MUSIC", "TAB_VISIBILITY visible=$it")
                                }
                                Text("Hiding a tab does not stop active playback or music.", color = Color.LightGray,
                                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                                Text("Music", style = MaterialTheme.typography.titleLarge, color = Color.White)
                                TvSettingsToggle("Use desktop site for Music", musicDesktopMode) {
                                    musicDesktopMode = it; settings.musicDesktopMode = it
                                    Log.i("JA_MUSIC", "DESKTOP_SITE_PREFERENCE enabled=$it")
                                }
                                Text("Requests Deezer's desktop website. After changing this, Stop Music and reopen it. Saved Deezer login data is kept.",
                                    color = Color.LightGray, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                                JaMusicDiagnosticsSettings()
                                Spacer(Modifier.height(8.dp))
                                JaWeatherSettings(weather)
                            }
                            "Sources" -> {
                                Text("Preferred playback sources", style = MaterialTheme.typography.titleLarge, color = Color.White)
                                Text("Default source order: 1. VidSrc (vidsrc.sh), 2. VidFast (vidfast.vc), 3. ScottsMedia, 4. Movy (movy.sx), 5. 7Movies (7movies.ac), 6. CineJoy (cinejoy.pk). TV episodes first try the last working source for that same series and season. Movies retain the default order. Each TV season learns independently. Reset clears saved season preferences. Watch progress stays intact.",
                                    color = Color.LightGray, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                                TvFocusButton(onClick = { confirmPreferredSourceReset = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)) { Text("Reset preferred sources", color = Color.White) }
                                preferredSourceResetStatus?.let { Text(it, color = Color.LightGray, textAlign = TextAlign.Center) }
                                Spacer(Modifier.height(8.dp))
                                Text("VidSrc catalogue downloads", style = MaterialTheme.typography.titleLarge, color = Color.White)
                                TvSettingsToggle("Download VidSrc ID lists daily", dailyVidSrcDownloads) { JaVidSrcCatalog.setDailyDownloads(it) }
                                Text("When off, JA keeps using saved IDs and skips automatic downloads on launch or return. Turning this on checks whether the lists are overdue.",
                                    color = Color.LightGray, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                                if (vidSrcSnapshot.busy || (!vidSrcSnapshot.ready && dailyVidSrcDownloads)) Text(
                                    if (vidSrcSnapshot.ready) "Updating VidSrc catalogue…" else "Preparing VidSrc catalogue…",
                                    color = Color.LightGray, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                                vidSrcSnapshot.error?.let {
                                    Text(it, color = Color(0xFFFFA8A8), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                                    if (!vidSrcSnapshot.busy) TvFocusButton(onClick = { JaVidSrcCatalog.refreshOverdue(force = true) }) { Text("Retry source refresh") }
                                }
                                if (!vidSrcSnapshot.ready && !dailyVidSrcDownloads) Text(
                                    "No saved ID lists. Download once below to populate available movie listings.",
                                    color = Color(0xFFFFC48A), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                                TvFocusButton(onClick = { JaVidSrcCatalog.refreshOverdue(force = true) },
                                    enabled = !vidSrcSnapshot.busy) { Text("Download ID lists now") }
                                Spacer(Modifier.height(8.dp))
                                Text("TV episode release dates", style = MaterialTheme.typography.titleLarge, color = Color.White)
                                TvSettingsToggle("Show TV episodes one day after release", delayTvEpisodes) {
                                    delayTvEpisodes = it; settings.delayTvEpisodes = it
                                }
                                Text("When enabled, only episodes dated yesterday or earlier appear. Today's, future and missing dates are hidden. Uses this device's local calendar date. Turn off to show all episodes.",
                                    color = Color.LightGray, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                                Text("Settings and source preferences are saved on this device.", color = Color.LightGray,
                                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                            }
                        }
                        Text("The TMDB API provides metadata; JA is not endorsed or certified by TMDB.",
                            color = Color.LightGray, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    }
                    }
                }
                if (confirmPreferredSourceReset) AlertDialog(
                    onDismissRequest = { confirmPreferredSourceReset = false },
                    title = { Text("Reset preferred sources?") },
                    text = { Text("All titles will start with VidSrc, then VidFast, ScottsMedia, Movy, 7Movies and CineJoy. Saved TV-season preferences will be cleared. Watch progress is preserved.") },
                    confirmButton = {
                        TvFocusButton(onClick = {
                            val saved = preferredSourceReset.resetAll()
                            preferredSourceResetStatus = if (saved) "Season preferences cleared. Default order: VidSrc, VidFast, ScottsMedia, Movy, 7Movies, CineJoy."
                                else "Reset could not be saved. Please retry."
                            confirmPreferredSourceReset = false
                        }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("Reset all titles", color = Color.Black) }
                    },
                    dismissButton = {
                        TvFocusButton(onClick = { confirmPreferredSourceReset = false },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)) { Text("Cancel", color = Color.White) }
                    }
                )
            }
        } else if (selectedPerson != null) {
            val activePerson = selectedPerson!!
            key("person:${activePerson.id}") {
            TmdbPersonScreen(
                person = activePerson,
                repository = peopleRepo,
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
                watchedKeys = watched,
                episodeResumePositions = remember(history, item.id, item.type) {
                    history.filter { it.mediaId == item.id && it.mediaType == "tv" && it.episode > 0 }
                        .associate { (it.season to it.episode) to it.positionMs }
                },
                pendingCastFocusId = pendingCastFocus?.takeIf { it.first == "${item.type}:${item.id}" }?.second,
                onCastFocusRestored = { pendingCastFocus = null },
                pendingEpisodeFocus = pendingEpisodeFocus,
                pendingPlayFocus = pendingPlayFocus,
                onPlayFocusRestored = { pendingPlayFocus=false;pendingEpisodeFocus=null },
                onEpisodeFocusRestored = { pendingEpisodeFocus = null;pendingPlayFocus=false },
                onSetWatched = { s, e, value ->
                    scope.launch {
                        try {
                            PlaybackProgress.setWatched(dao, item.id, item.type, s, e, value)
                            refreshHistory()
                        } catch (problem: Exception) { error = "Could not save watched status. Please retry." }
                    }
                },
                isFavorite = history.any { it.mediaId == item.id && it.mediaType == item.type && it.favorite },
                onPlay = {
                    playbackReturnToPlay = true
                    if (item.type == "movie") play(item)
                    else (nextUnwatchedEpisode(item.id, visibleEpisodes, watched) ?: visibleEpisodes.firstOrNull())
                        ?.let { play(item, it.season, it.episode) }
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
                seasons = seasons,
                season = season,
                episodes = visibleEpisodes,
                episodesLoading = episodesLoading,
                episodesError = episodesError,
                onSeasonSelected = { chosen ->
                    pendingEpisodeFocus = null
                    season = chosen
                    episodes = emptyList()
                    episodesLoading = true
                    episodesError = null
                    // Retry even when the selected season is clicked again.
                    episodeReload += 1

                },
                onEpisodePlay = { ep -> playbackReturnToPlay=false;play(item, ep.season, ep.episode) },
                restoreScrollY = if (pendingPlayFocus) null else titleScrollBookmarks["${item.type}:${item.id}"],
                onSaveScrollY = { offset -> titleScrollBookmarks["${item.type}:${item.id}"] = offset }
            )
            }
        } else {
            if (tab == "Search") {
                JaSearchScreen(
                    subRowFocus = subRequester,
                    returnFocus = titleReturnFocus,
                    query = search, onQueryChange = { search = it },
                    recentSearches = recentSearches,
                    onRunRecentSearch = { entry ->
                        search = entry.query
                        searchMode = entry.mode
                        runSearch(entry.query, entry.mode)
                    },
                    onClearRecentSearches = { recentSearches = searchHistoryStore.clear() },
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
                    results = searchResponse.copy(titles = searchResponse.titles.map { marked(it) }),
                    selectedGenre = searchGenre,
                    genreTitles = outsideOther(searchGenreTitles).map { marked(it) },
                    genreLoading = searchGenreLoading || (searchGenre?.type == "movie" && !vidSrcSnapshot.ready),
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
            } else if (tab == "IPTV") {
                JaLiveTvScreen(liveTv, weather, onMainMenu = { focusMain() },
                    matchTvFrameRate = matchTvFrameRate,
                    matchTvResolution = matchTvResolution,
                    matchStreamQuality = matchStreamQuality,
                    modifier = Modifier.fillMaxWidth().weight(1f))
            } else if (tab == "Movies" || tab == "TV") {
                val isMovieTab = tab == "Movies"
                val activeView = if (isMovieTab) moviesView else tvView
                JaUnscaledTopBar {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ((if (isMovieTab) listOf("Popular", "Genres", "Other") else listOf("Popular", "Genres", "Networks")) + listOf("Favorites", "Continue")).forEach { view ->
                            TvSecondaryButton(
                                text = view,
                                modifier = Modifier.then(if(activeView==view) Modifier.focusRequester(subRequester) else Modifier)
                                    .onFocusChanged { if(it.isFocused) navigationLevel=1 },
                                selected = activeView == view,
                                onClick = { if (isMovieTab) moviesView = view else tvView = view }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (activeView == "Favorites") {
                    val favoriteType = if (isMovieTab) "movie" else "tv"
                    val orderedIds = if (favoriteType == "movie") movieFavoriteIds else tvFavoriteIds
                    val favoriteById = history.asSequence()
                        .filter { it.favorite && it.mediaType == favoriteType }
                        .distinctBy { it.mediaId }
                        .associateBy { it.mediaId }
                    val favoriteTitles = orderedIds.mapNotNull { favoriteById[it] }
                        .map { TitleItem(it.mediaId, it.title, "", it.poster, null, it.mediaType) }
                    Text(if (isMovieTab) "Favorite Movies" else "Favorite TV", style = MaterialTheme.typography.titleLarge, color = Color.White)
                    Spacer(Modifier.height(14.dp))
                    if (favoriteTitles.isEmpty()) {
                        Text("No favorite ${if (favoriteType == "movie") "movies" else "TV shows"} yet.", color = Color.LightGray)
                    } else {
                        val gridState = if (favoriteType == "movie") favoriteMovieGridState else favoriteTvGridState
                        Box(Modifier.fillMaxWidth().weight(1f)) {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(minSize = 152.dp),
                                state = gridState,
                                modifier = Modifier.fillMaxSize().onFocusChanged { if(it.hasFocus) navigationLevel=2 }.focusGroup(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                                contentPadding = PaddingValues(bottom = 24.dp)
                            ) {
                                gridItems(favoriteTitles, key = { "favorite-${it.type}-${it.id}" }, contentType = { "poster" }) { item ->
                                    JaSearchTitleCard(marked(item), returnFocus = titleReturnFocus,
                                        returnKey = "$tab:Favorites:${item.type}:${item.id}") { open(item) }
                                }
                            }
                        }
                    }
                } else if (activeView == "Continue") {
                    val continueType = if (isMovieTab) "movie" else "tv"
                    val continueItems = remember(history, continueType) {
                        history.filter { it.mediaType == continueType && it.positionMs > 0 &&
                            (continueType == "movie" || it.episode > 0) }
                            .sortedByDescending { it.updatedAt }
                            .distinctBy { it.mediaId }
                    }
                    Text(if (isMovieTab) "Continue Movies" else "Continue TV", style = MaterialTheme.typography.titleLarge, color = Color.White)
                    Spacer(Modifier.height(14.dp))
                    if (continueItems.isEmpty()) {
                        Text("No ${if (continueType == "movie") "movies" else "TV shows"} to continue yet.", color = Color.LightGray)
                    } else {
                        val gridState = if (continueType == "movie") continueMovieGridState else continueTvGridState
                        Box(Modifier.fillMaxWidth().weight(1f)) {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(minSize = 152.dp), state = gridState,
                                modifier = Modifier.fillMaxSize().onFocusChanged { if(it.hasFocus) navigationLevel=2 }.focusGroup(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                                contentPadding = PaddingValues(bottom = 24.dp)
                            ) {
                                gridItems(continueItems, key = { "continue-${it.mediaType}-${it.mediaId}" }, contentType = { "poster" }) { saved ->
                                    val title = TitleItem(saved.mediaId, saved.title, "", saved.poster, null, saved.mediaType)
                                    JaSearchTitleCard(marked(title),
                                        badge = JaResumeLabels.continueLabel(saved.mediaType, saved.season,
                                            saved.episode, saved.positionMs),
                                        returnFocus = titleReturnFocus,
                                        returnKey = "$tab:Continue:${title.type}:${title.id}"
                                    ) { open(title) }
                                }
                            }
                        }
                    }
                } else if (!isMovieTab && activeView == "Networks") {
                    JaNetworksScreen(
                        repo = networksRepo, state = networksState, returnFocus = titleReturnFocus,
                        marked = { marked(it) }, onOpen = { open(it) },
                        onBackToNavigation = { focusSub() },
                        modifier = Modifier.fillMaxWidth().weight(1f).onFocusChanged { if(it.hasFocus) navigationLevel=2 }.focusGroup()
                    )
                } else if (activeView == "Popular" || activeView == "Other") {
                    val isOther = isMovieTab && activeView == "Other"
                    // The TV and Movies Popular lists share layout only, not data or scroll state.
                    val titles = remember(isOther,isMovieTab,otherDisplayTitles,popularTitles,tvPopularTitles,outsideOther) { if (isOther) otherDisplayTitles else if (isMovieTab) outsideOther(popularTitles) else tvPopularTitles }
                    val gridState = if (isOther) otherGridState else if (isMovieTab) popularGridState else tvPopularGridState
                    val loading = if (isOther) otherLoading else if (isMovieTab) popularLoading || !vidSrcSnapshot.ready else tvPopularLoading
                    val loadedPage = if (isOther) otherPage else if (isMovieTab) popularPage else tvPopularPage
                    val totalPages = if (isOther) otherTotalPages else if (isMovieTab) popularTotalPages else tvPopularTotalPages
                    val pageError = if (isOther) otherError else if (isMovieTab) popularError else tvPopularError
                    var pageFocusId by remember(tab, activeView) { mutableStateOf<Int?>(null) }
                    LaunchedEffect(pageFocusId) {
                        val id = pageFocusId ?: return@LaunchedEffect
                        val index = titles.indexOfFirst { it.id == id }
                        if (index >= 0) gridState.scrollToItem(index)
                    }
                    Column(Modifier.jaWeatherIntroWidth()) {
                                Text(if (isOther) "Upcoming & CAM Movies" else if (isMovieTab) "Popular Movies" else "Popular TV Shows",
                                    style = MaterialTheme.typography.titleLarge, color = Color.White)
                                Text("${titles.size} titles loaded", color = Color.LightGray)

                                if (isOther && titles.isEmpty() && !loading && loadedPage > 0 && pageError == null) {
                                    Text(if (loadedPage < totalPages) "No upcoming matches on this page. Load More to keep browsing."
                                        else "No upcoming or confirmed CAM movies found.", color = Color.LightGray)
                                }
                            }
                    Spacer(Modifier.height(14.dp))
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 152.dp),
                        state = gridState,
                        modifier = Modifier.fillMaxSize().jaWeatherContentStart().onFocusChanged { if(it.hasFocus) navigationLevel=2 }.focusGroup(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        gridItems(titles, key = { "popular-${it.type}-${it.id}" }, contentType={"poster"}) { item ->
                            JaSearchTitleCard(marked(item),
                                badge = if (isOther) { if (item.id in vidSrcQuality.cams) "CAM" else "Upcoming" } else null,
                                returnFocus = titleReturnFocus, returnKey = "$tab:$activeView:${item.type}:${item.id}",
                                restoreFocus = pageFocusId == item.id,
                                onFocusRestored = { pageFocusId = null }
                            ) { open(item) }
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
                        if (loadedPage == 0 || loadedPage < totalPages) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "popular-more") {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                                    TvFocusButton(
                                        onClick = {
                                            if (!loading) {
                                                val index = lastLoadedRowIndex(gridState, titles.size, 0)
                                                pageFocusId = titles.getOrNull(index)?.id
                                                if (isOther) loadMoreOther() else if (isMovieTab) loadMorePopular() else loadMorePopularTV()
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = accent)
                                    ) { Text(if (loading) "Loading…" else if (loadedPage == 0) "Retry" else "Load More", color = Color.Black) }
                                }
                            }
                        }
                    }
                } else {
                    Text(if(isMovieTab) "Movie Genres" else "TV Genres", modifier=Modifier.jaWeatherIntroWidth(), style=MaterialTheme.typography.titleLarge, color=Color.White)
                    JaGenreRows(
                        tab = tab, rows = genreRows, loading = genresLoading,
                        sectionState = sectionScrollStates.getOrPut(tab) { LazyListState() },
                        rowStates = genreScrollStates, rowLoading = genreRowLoading, rowErrors = genreRowErrors,
                        returnFocus = titleReturnFocus,
                        jump = genreJumpStates.getOrPut(tab) { JaRowJumpState("$tab:Genres") },
                        visibleTitles = outsideOther, marked = { marked(it) },
                        onOpen = { open(it) },
                        onLoadMore = { row -> loadMoreGenreRow(if (isMovieTab) "movie" else "tv", row) },
                        onBackToNavigation = { focusSub() },
                        modifier = Modifier.fillMaxWidth().weight(1f).onFocusChanged { if(it.hasFocus) navigationLevel=2 }.focusGroup()
                    )
                }
            }
        }
        }
    }
    }
    }
}

// Compact second-level navigation for Movies/TV catalog, Favorites, and Continue views.
@Composable
private fun TvSecondaryButton(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Button(
        onClick = onClick,
        modifier = modifier
            .height(26.dp)
            .onFocusChanged { focused = it.isFocused },
        border = jaButtonFocusBorder(focused),
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
internal fun TvFocusButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Button(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused },
        border = jaButtonFocusBorder(focused),
        colors = colors,
        enabled = enabled,
        content = content
    )
}

@Composable
private fun TvFocusTextButton(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    var focused by remember { mutableStateOf(false) }
    TextButton(
        onClick = onClick,
        modifier = Modifier.onFocusChanged { focused = it.isFocused },
        border = jaButtonFocusBorder(focused),
        content = content
    )
}

@Composable internal fun PosterTile(item: TitleItem, returnFocus: JaTitleReturnFocus,
    returnKey: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val requester = rememberTitleReturnRequester(returnFocus, returnKey)
    LaunchedEffect(item.id,item.type) { JaVidSrcCatalog.observe(item) }
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier.width(140.dp).focusRequester(requester)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) returnFocus.restored(returnKey) }
            .then(if (focused) Modifier.border(3.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
            .clickable { returnFocus.opened(returnKey); onClick() }
            .padding(3.dp)
    ) {
        AsyncImage(item.posterUrl, item.title, Modifier.fillMaxWidth().height(190.dp), contentScale = ContentScale.Crop)
        Text((if (item.watched) "✓ " else "") + item.title, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 2,
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
    matchStreamQuality: Boolean = false,
    adBlockerMode: String = JaGeckoAdBlockers.UBLOCK,
    currentSourceLabel: String = "Source",
    currentSourceId: String = "unknown",
    hasNextSource: Boolean = false,
    onTryNextSource: () -> Unit = {},
    onRetry: () -> Unit = {},
    onDisableBlockerAndRetry: () -> Unit = {},
    onPositionChanged: (Long) -> Unit = {},
    resumePositionMs: Long = 0L,
    restartFromBeginning: Boolean = false,
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
                if (restartFromBeginning || resumePositionMs > 0) player.seekTo(resumePositionMs)
                while (true) {
                    delay(5_000L)
                    val position = player.currentPosition.coerceAtLeast(0)
                    onPositionChanged(position)
                    PlaybackProgress.save(dao, r, position, player.duration)
                }
            }
            DisposableEffect(player, request) {
                onDispose {
                    val r = request
                    PlaybackProgress.save(dao, r, player.currentPosition.coerceAtLeast(0), player.duration)
                    player.release()
                }
            }
            Box(Modifier.fillMaxSize()) {
                AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = true } }, modifier = Modifier.fillMaxSize())
                TvFocusButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart)) { Text("Back") }
            }
        }
        is PlaybackSource.Embed -> {
            val embedUrl = remember(source) { JaVidFastBridge.resumeUrl(source.url,resumePositionMs) }
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
                    url = embedUrl,
                    resumePositionMs = resumePositionMs,
                    restartFromBeginning = restartFromBeginning,
                    onProgress = { position, duration ->
                        onPositionChanged(position)
                        PlaybackProgress.save(dao, request, position, duration)
                    },
                    onPlaybackStalled = {
                        recoveryReason = "Playback stopped advancing. Retry or choose another server."
                    },
                    showRestore = showRestore,
                    showDiagnostics = showDiagnostics,
                    adBlockerMode = adBlockerMode,
                    diagnosticsLabel = currentSourceLabel,
                    matchStreamQuality = matchStreamQuality && matchTvFrameRate && matchTvResolution,
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
private fun jaMainTabNames(showIptv: Boolean, showMusic: Boolean): List<String> =
    listOf("Movies", "TV") + (if (showIptv) listOf("IPTV") else emptyList()) +
        (if (showMusic) listOf("Music") else emptyList()) + listOf("Search", "Settings")

internal class PlayerControlSettings(context: Context) {
    private val prefs = context.getSharedPreferences("ja_player_controls", Context.MODE_PRIVATE)
    private val isTelevision =
        (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    var delayTvEpisodes: Boolean
        get() = prefs.getBoolean("tv_episode_release_delay", true)
        set(value) { prefs.edit().putBoolean("tv_episode_release_delay", value).apply() }
    var musicDesktopMode: Boolean
        get() = prefs.getBoolean("music_desktop_site", false)
        set(value) { prefs.edit().putBoolean("music_desktop_site", value).apply() }
    var showMusicTab: Boolean
        get() = prefs.getBoolean("show_music_tab", false)
        set(value) { prefs.edit().putBoolean("show_music_tab", value).apply() }
    var showIptvTab: Boolean
        get() = prefs.getBoolean("show_iptv_tab", true)
        set(value) { prefs.edit().putBoolean("show_iptv_tab", value).apply() }
    var iptvBuffer: JaIptvBuffer
        get() {
            val saved = prefs.getString("iptv_buffer", null)
            return JaIptvBuffer.entries.firstOrNull { it.name == saved } ?: JaIptvBuffer.DEFAULT
        }
        set(value) { prefs.edit().putString("iptv_buffer", value.name).apply() }
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
        get() = isTelevision && prefs.getBoolean("tv_match_resolution", true)
        set(value) { prefs.edit().putBoolean("tv_match_resolution", value).apply() }
    var matchStreamQuality: Boolean
        get() = isTelevision && prefs.getBoolean("tv_match_stream_quality", false)
        set(value) { prefs.edit().putBoolean("tv_match_stream_quality", value).apply() }
    var adBlockerMode: String
        get() = JaGeckoAdBlockers.normalized(prefs.getString("ad_blocker_mode", JaGeckoAdBlockers.UBLOCK) ?: JaGeckoAdBlockers.UBLOCK)
        set(value) { prefs.edit().putString("ad_blocker_mode", JaGeckoAdBlockers.normalized(value)).apply() }
}

@Composable
internal fun TvSettingsToggle(label: String, checked: Boolean, enabled: Boolean = true,
    onChanged: (Boolean) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth()
        .onFocusChanged { focused = it.isFocused }
        .then(if (focused) Modifier.border(3.dp, Color.White, RoundedCornerShape(10.dp)) else Modifier)
        .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChanged)
        .padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(label, color = if (enabled) Color.White else Color.Gray, modifier = Modifier.padding(start = 8.dp))
    }
}
