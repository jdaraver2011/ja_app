package com.example.ja

import android.content.Intent
import android.support.v4.media.session.MediaSessionCompat
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.media.MediaPlaybackManager
import androidx.car.app.media.model.MediaPlaybackTemplate
import androidx.car.app.model.*
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

/** Android Auto renders these API-8 templates; it never displays JA's phone/video activity. */
@androidx.car.app.annotations.RequiresCarApi(8)
class JaMusicCarService : CarAppService() {
    override fun createHostValidator(): HostValidator = if (BuildConfig.DEBUG) HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        else HostValidator.Builder(this).build() // This branch is a debug/device test, not a production car release.
    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = if (intent.action == "androidx.car.app.media.action.SHOW_MEDIA_PLAYBACK") MusicCarPlaying(carContext) else MusicCarBrowse(carContext)
        override fun onNewIntent(intent: Intent) {
            if (intent.action == "androidx.car.app.media.action.SHOW_MEDIA_PLAYBACK") {
                carContext.getCarService(androidx.car.app.ScreenManager::class.java).push(MusicCarPlaying(carContext))
            }
        }
    }
}

@androidx.car.app.annotations.RequiresCarApi(8)
private class MusicCarBrowse(context: CarContext, private val queueOnly: Boolean = false) : Screen(context) {
    private var ready = false
    private var failed = ""
    private var tracks = emptyList<JaMusicCatalog.Track>()
    init {
        lifecycleScope.launch {
            JaMusicLabService.state.map { it.account to it.queue.map { track -> track.id } }.distinctUntilChanged().collect {
                if (queueOnly) tracks = JaMusicLabService.state.value.queue
                invalidate()
            }
        }
        lifecycleScope.launch {
            try {
                JaMusicLabService.ensureSession(carContext)
                withTimeout(10000) {
                    while (JaMusicLabService.platformToken() == null) delay(100)
                }
                val token = MediaSessionCompat.Token.fromToken(JaMusicLabService.platformToken())
                    ?: throw IllegalStateException("No music session")
                carContext.getCarService(MediaPlaybackManager::class.java).registerMediaPlaybackToken(token)
                tracks = if (queueOnly) JaMusicLabService.state.value.queue else withContext(Dispatchers.IO) { JaMusicCatalog.tracks() }
                ready = true
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed = "Open Music on your phone to check the connection." }
            invalidate()
        }
    }
    override fun onGetTemplate(): Template {
        val start = if (queueOnly) Action.BACK else Action.APP_ICON
        if (failed.isNotBlank()) return MessageTemplate.Builder(failed).setTitle("JA Music").setHeaderAction(start).build()
        if (!ready) return MessageTemplate.Builder("Connecting to JA Music…").setLoading(true).setTitle("JA Music").setHeaderAction(start).build()
        val headerBuilder = Header.Builder().setTitle(if (queueOnly) "Queue" else "JA Music").setStartHeaderAction(start)
            .addEndHeaderAction(Action.Builder().setTitle("Now Playing")
                .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ja_music_car_icon)).build())
                .setOnClickListener { screenManager.push(MusicCarPlaying(carContext)) }.build())
        if (!queueOnly) headerBuilder.addEndHeaderAction(Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, android.R.drawable.ic_menu_search)).build())
            .setOnClickListener { screenManager.push(MusicCarSearch(carContext)) }.build())
        val header = headerBuilder.build()
        val rows = RowSection.Builder().setTitle(if (queueOnly) "Up Next" else "Top Tracks")
        if (JaMusicLabService.state.value.account.isBlank()) {
            rows.addItem(Row.Builder().setTitle("Sign in to Deezer on your phone").addText("Music > Account in JA").build())
        } else tracks.take(40).forEachIndexed { index, track ->
            rows.addItem(Row.Builder().setTitle(track.title.ifBlank { "Track ${track.id}" }).addText(track.artist)
                .setOnClickListener {
                    JaMusicLabService.playTracks(carContext, tracks, index)
                    screenManager.push(MusicCarPlaying(carContext))
                }.build())
        }
        return SectionedItemTemplate.Builder().setHeader(header).addSection(rows.build()).build()
    }
}

@androidx.car.app.annotations.RequiresCarApi(8)
private class MusicCarPlaying(context: CarContext) : Screen(context) {
    private var ready = false
    private var failed = false
    init {
        lifecycleScope.launch {
            try {
                JaMusicLabService.ensureSession(carContext)
                withTimeout(10000) { while (JaMusicLabService.platformToken() == null) delay(100) }
                val token = MediaSessionCompat.Token.fromToken(JaMusicLabService.platformToken()) ?: error("No session")
                carContext.getCarService(MediaPlaybackManager::class.java).registerMediaPlaybackToken(token)
                ready = true
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed = true }
            invalidate()
        }
    }
    override fun onGetTemplate(): Template {
        if (!ready) return MessageTemplate.Builder(if (failed) "Open Music on your phone to reconnect." else "Connecting to JA Music…")
            .setTitle("JA Music").setHeaderAction(Action.BACK).setLoading(!failed).build()
        return MediaPlaybackTemplate.Builder().setHeader(Header.Builder()
        .setTitle("Now Playing").setStartHeaderAction(Action.BACK)
        .addEndHeaderAction(Action.Builder().setTitle("Queue")
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ja_music_car_icon)).build())
            .setOnClickListener { screenManager.popToRoot(); screenManager.push(MusicCarBrowse(carContext, true)) }.build()).build()).build()
    }
}

@androidx.car.app.annotations.RequiresCarApi(8)
private class MusicCarSearch(context: CarContext) : Screen(context) {
    private var tracks = emptyList<JaMusicCatalog.Track>()
    private var loading = false
    private var error = "Search songs or artists"
    private var request: Job? = null
    override fun onGetTemplate(): Template {
        val callback = object : SearchTemplate.SearchCallback {
            override fun onSearchSubmitted(searchText: String) {
                request?.cancel(); loading = true; invalidate()
                request = lifecycleScope.launch {
                    try { tracks = withContext(Dispatchers.IO) { JaMusicCatalog.tracks(searchText) }; error = "No songs found" }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { error = "Search unavailable. Try again."; tracks = emptyList() }
                    finally { loading = false; invalidate() }
                }
            }
        }
        val builder = SearchTemplate.Builder(callback).setHeaderAction(Action.BACK).setSearchHint("Songs or artists").setLoading(loading)
        if (!loading) {
            val rows = ItemList.Builder().setNoItemsMessage(error)
            tracks.take(40).forEachIndexed { index, track ->
                rows.addItem(Row.Builder().setTitle(track.title).addText(track.artist).setOnClickListener {
                    JaMusicLabService.playTracks(carContext, tracks, index)
                    screenManager.push(MusicCarPlaying(carContext))
                }.build())
            }
            builder.setItemList(rows.build())
        }
        builder.setActionStrip(ActionStrip.Builder().addAction(Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ja_music_car_icon)).build())
            .setOnClickListener { screenManager.push(MusicCarPlaying(carContext)) }.build()).build())
        return builder.build()
    }
}
