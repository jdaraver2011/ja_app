package com.example.ja

import android.app.PendingIntent
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.*
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.*
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaLibraryService.LibraryParams
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** One native player/session shared by JA, notifications, headsets and Android Auto. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class JaMusicLabService : MediaLibraryService() {
    companion object {
        private const val MUSIC_CHANNEL = "ja-native-music"
        private const val MUSIC_NOTIFICATION = 251
        private var instance: JaMusicLabService? = null
        // Public media-browser binding does not authorize arbitrary raw startService commands.
        private val commandNonce = java.util.UUID.randomUUID().toString()
        private var videoActive = false
        data class State(val running: Boolean = false, val busy: Boolean = false,
            val message: String = "Sign in to Deezer to play music.", val account: String = "",
            val title: String = "", val artist: String = "", val artwork: String = "", val playing: Boolean = false,
            val position: Long = 0, val duration: Long = 0, val queueIndex: Int = 0,
            val queue: List<JaMusicCatalog.Track> = emptyList(), val shuffle: Boolean = false, val repeat: Int = 0)
        private val mutable = MutableStateFlow(State())
        val state = mutable.asStateFlow()
        fun connect(context: Context, arl: String) {
            val value = arl.trim()
            if (!Regex("[A-Za-z0-9_-]{64,512}").matches(value)) {
                mutable.value = mutable.value.copy(message = "Enter a valid Deezer session."); return
            }
            instance?.let { it.connectJob?.cancel(); it.searchJob?.cancel(); it.player.stop(); it.player.clearMediaItems() }
            try { JaMusicLabVault(context).save(value) }
            catch (_: Exception) { mutable.value = mutable.value.copy(message = "Could not securely save the session."); return }
            launch(context, "CONNECT")
        }
        fun playSearch(context: Context, query: String) {
            JaMusicService.stop(context)
            context.startService(Intent(context, JaMusicLabService::class.java).setAction("SEARCH")
                .putExtra("ja.music.internal", commandNonce).putExtra("query", query.take(200)))
        }
        fun restore(context: Context) = launch(context, "CONNECT")
        fun playTracks(context: Context, tracks: List<JaMusicCatalog.Track>, index: Int = 0) {
            if (tracks.isEmpty()) return
            val selected = index.coerceIn(0, tracks.lastIndex)
            val start = if (tracks.size > 500) (selected - 250).coerceIn(0, tracks.size - 500) else 0
            val window = tracks.drop(start).take(500)
            JaMusicCatalog.remember(window)
            JaMusicService.stop(context)
            ContextCompat.startForegroundService(context, Intent(context, JaMusicLabService::class.java)
                .setAction("QUEUE").putExtra("ja.music.internal", commandNonce).putStringArrayListExtra("queue", ArrayList(window.map { it.id }))
                .putExtra("index", selected - start))
        }
        fun playTrack(context: Context, input: String) {
            val id = parseTrack(input)
            if (id == null) { mutable.value = mutable.value.copy(message = "Enter a Deezer track ID or full track link."); return }
            playTracks(context, listOf(JaMusicCatalog.known(id)))
        }
        internal fun parseTrack(input: String): String? {
            val value = input.trim()
            if (Regex("[1-9][0-9]{0,18}").matches(value)) return value
            return try {
                val uri = java.net.URI(value)
                if (uri.scheme != "https" || uri.host !in listOf("deezer.com", "www.deezer.com") || uri.userInfo != null) null
                else Regex("(?:/[a-z]{2})?/track/([1-9][0-9]{0,18})/?").matchEntire(uri.path)?.groupValues?.get(1)
            } catch (_: Exception) { null }
        }
        private fun launch(context: Context, action: String) {
            context.startService(Intent(context, JaMusicLabService::class.java).setAction(action).putExtra("ja.music.internal", commandNonce))
        }
        fun playPause() { instance?.let { if (it.player.isPlaying) it.player.pause() else if (!videoActive) it.player.play() } }
        fun toggleShuffle() { instance?.player?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
        fun cycleRepeat() { instance?.player?.let { it.repeatMode = (it.repeatMode + 1) % 3 } }
        fun next() { instance?.player?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() } }
        fun previous() { instance?.player?.seekToPrevious() }
        fun seekTo(position: Long) { instance?.player?.seekTo(position.coerceAtLeast(0)) }
        fun stop(context: Context) { instance?.searchJob?.cancel(); instance?.player?.let { it.stop(); it.clearMediaItems() };
            mutable.value = State(message = "Music stopped.", account = mutable.value.account); context.stopService(Intent(context, JaMusicLabService::class.java)) }
        fun logout(context: Context) {
            JaMusicLabVault(context).clear()
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            android.webkit.CookieManager.getInstance().flush()
            instance?.let {
                it.connectJob?.cancel(); it.searchJob?.cancel(); it.clearCacheOnDestroy = true
                it.player.stop(); it.player.clearMediaItems()
                it.scope.launch(Dispatchers.IO) { it.cache.keys.toList().forEach { key -> runCatching { it.cache.removeResource(key) } } }
                it.stopSelf()
            }
                ?: File(context.cacheDir, "music-lab").deleteRecursively()
            mutable.value = State(running = instance != null, message = "Signed out. Session and temporary audio cache cleared.")
        }
        fun setVideoActive(active: Boolean) { videoActive = active; if (active) instance?.player?.pause() }
        fun keepsProcessAlive(): Boolean = instance?.let { it.player.playWhenReady || mutable.value.busy } == true
        internal fun platformToken(): android.media.session.MediaSession.Token? = instance?.session?.platformToken
        internal fun ensureSession(context: Context) { if (instance == null || mutable.value.account.isBlank()) restore(context) }
        internal fun mediaItem(track: JaMusicCatalog.Track): MediaItem = MediaItem.Builder().setMediaId(track.id)
            .setUri("ja-deezer://track/${track.id}").setMimeType(MimeTypes.AUDIO_MPEG)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).setAlbumTitle(track.album)
                .setArtworkUri(track.art.takeIf { it.startsWith("https://") }?.let { Uri.parse(it) })
                .setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build()).build()
        private fun folder(id: String, title: String) = MediaItem.Builder().setMediaId(id)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build()).build()
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: ExoPlayer
    private var session: MediaLibrarySession? = null
    private lateinit var cache: SimpleCache
    private var clearCacheOnDestroy = false
    private var connectJob: Job? = null
    private var searchJob: Job? = null
    private var expectedDuration = 0L
    override fun onCreate() {
        super.onCreate(); instance = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(MUSIC_CHANNEL, "JA Music", NotificationManager.IMPORTANCE_LOW))
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this)
            .setNotificationId(MUSIC_NOTIFICATION).setChannelId(MUSIC_CHANNEL)
            .setChannelName(R.string.ja_music_notification_channel).build())
        cache = SimpleCache(File(cacheDir, "music-lab"), LeastRecentlyUsedCacheEvictor(256L * 1024 * 1024), StandaloneDatabaseProvider(this))
        val cached = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(
            DefaultHttpDataSource.Factory().setUserAgent("JA-Music/2.5.2").setAllowCrossProtocolRedirects(false)
                .setConnectTimeoutMs(20000).setReadTimeoutMs(20000))
        val audioSource = JaMusicTrackSource.Factory(this, cached) { account, track ->
            scope.launch {
                if (player.currentMediaItem?.mediaId == track.id) {
                    expectedDuration = track.duration
                    val known = JaMusicCatalog.known(track.id).copy(title = track.title, artist = track.artist, seconds = track.duration / 1000,
                        album = track.album, art = track.art.ifBlank { JaMusicCatalog.known(track.id).art })
                    JaMusicCatalog.remember(listOf(known))
                    player.replaceMediaItem(player.currentMediaItemIndex, mediaItem(known))
                    mutable.value = mutable.value.copy(account = account.name, title = track.title, artist = track.artist,
                        message = "Account-authorized full-track stream • MP3 128 kbps")
                    Log.i("JA_MUSIC_LAB", "AUDIO_RESOLVED id=${track.id} expectedMs=${track.duration}")
                }
            }
        }
        val renderers = object : androidx.media3.exoplayer.DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink {
                val tee = androidx.media3.exoplayer.audio.TeeAudioProcessor(object : androidx.media3.exoplayer.audio.TeeAudioProcessor.AudioBufferSink {
                    private var pcm16 = true
                    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
                        pcm16 = encoding == C.ENCODING_PCM_16BIT
                        JaMusicVisualization.pcm.format(channelCount)
                    }
                    override fun handleBuffer(buffer: java.nio.ByteBuffer) {
                        if (pcm16) JaMusicVisualization.pcm.capture16(buffer, android.os.SystemClock.elapsedRealtime())
                    }
                })
                return androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false).setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                    .setAudioProcessors(arrayOf(tee)).build()
            }
        }
        player = ExoPlayer.Builder(this, renderers).setMediaSourceFactory(DefaultMediaSourceFactory(audioSource)).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
            addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) { publish() }
                override fun onMediaItemTransition(item: MediaItem?, reason: Int) { expectedDuration = item?.mediaId?.let { JaMusicCatalog.known(it).seconds * 1000 } ?: 0; publish() }
                override fun onPlaybackStateChanged(state: Int) {
                    Log.i("JA_MUSIC_LAB", "PLAYER_STATE state=$state")
                    if (state == Player.STATE_READY && expectedDuration > 45000 && player.duration in 1 until expectedDuration - 10000) {
                        player.pause(); mutable.value = mutable.value.copy(message = "Returned audio is shorter than the full track; stopped.")
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    val reason = generateSequence<Throwable>(error) { it.cause }.mapNotNull { it.message }
                        .firstOrNull { Regex("(?:SESSION|ACCOUNT|TRACK|AUDIO)_[A-Z0-9_]+").matches(it) } ?: error.errorCodeName
                    mutable.value = mutable.value.copy(busy = false, playing = false, message = "Playback result: $reason")
                    Log.i("JA_MUSIC_LAB", "PLAYBACK_FAILED reason=$reason")
                }
            })
        }
        val guarded = object : ForwardingPlayer(player) {
            override fun play() { if (!videoActive) super.play() }
            override fun setPlayWhenReady(playWhenReady: Boolean) { super.setPlayWhenReady(playWhenReady && !videoActive) }
        }
        val open = PendingIntent.getActivity(this, 251, Intent(this, MainActivity::class.java).putExtra(JaMusicService.OPEN_MUSIC, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val musicSession = MediaLibrarySession.Builder(this, guarded, LibraryCallbacks()).setSessionActivity(open).build()
        session = musicSession
        // Local UI controls the player directly, so no browser/controller bind is guaranteed.
        // Registration installs Media3's notification and foreground-lifecycle listeners now.
        addSession(musicSession)
        Log.i("JA_MUSIC_LAB", "SESSION_REGISTERED count=${sessions.size}")
        mutable.value = mutable.value.copy(running = true)
        scope.launch { while (isActive) { delay(1000); publish() } }
    }
    private fun publish() {
        val metadata = player.currentMediaItem?.mediaMetadata
        val queue = (0 until player.mediaItemCount).map { JaMusicCatalog.known(player.getMediaItemAt(it).mediaId) }
        mutable.value = mutable.value.copy(playing = player.isPlaying, position = player.currentPosition.coerceAtLeast(0),
            duration = player.duration.coerceAtLeast(0), busy = player.playbackState == Player.STATE_BUFFERING || connectJob?.isActive == true || searchJob?.isActive == true,
            title = metadata?.title?.toString().orEmpty(), artist = metadata?.artist?.toString().orEmpty(),
            artwork = metadata?.artworkUri?.toString().orEmpty(), queueIndex = player.currentMediaItemIndex.coerceAtLeast(0), queue = queue, shuffle = player.shuffleModeEnabled, repeat = player.repeatMode)
    }
    private fun beginForegroundPlayback(): Boolean {
        val current = session ?: return false
        val open = PendingIntent.getActivity(this, 251, Intent(this, MainActivity::class.java)
            .putExtra(JaMusicService.OPEN_MUSIC, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val preparing = Notification.Builder(this, MUSIC_CHANNEL).setSmallIcon(R.drawable.ja_music_car_icon)
            .setContentTitle("JA Music").setContentText("Preparing playback…").setContentIntent(open)
            .setCategory(Notification.CATEGORY_TRANSPORT).setOngoing(true)
            .setStyle(Notification.MediaStyle().setMediaSession(current.platformToken)).build()
        return try {
            // Satisfy startForegroundService immediately. Media3 replaces this same notification
            // ID with its normal controls and owns foreground/demotion after player state changes.
            ServiceCompat.startForeground(this, MUSIC_NOTIFICATION, preparing,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            Log.i("JA_MUSIC_LAB", "FOREGROUND_READY sessionAdded=${isSessionAdded(current)}")
            true
        } catch (_: IllegalStateException) {
            mutable.value = mutable.value.copy(message = "Android blocked music service startup. Open JA and try again.")
            Log.w("JA_MUSIC_LAB", "FOREGROUND_START_BLOCKED")
            stopSelf(); false
        } catch (_: SecurityException) {
            mutable.value = mutable.value.copy(message = "Music service permission was denied by Android.")
            Log.w("JA_MUSIC_LAB", "FOREGROUND_PERMISSION_DENIED")
            stopSelf(); false
        }
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action in listOf("CONNECT", "QUEUE", "SEARCH") && intent?.getStringExtra("ja.music.internal") != commandNonce)
            return START_NOT_STICKY
        if (intent?.action == "QUEUE" && !beginForegroundPlayback()) return START_NOT_STICKY
        val result = super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            "CONNECT" -> {
                connectJob?.cancel()
                mutable.value = mutable.value.copy(busy = true, message = "Checking Deezer session…")
                connectJob = scope.launch {
                    try {
                        val account = withContext(Dispatchers.IO) {
                            val arl = JaMusicLabVault(this@JaMusicLabService).read() ?: throw JaMusicLabFailure("SESSION_REQUIRED")
                            val client = JaMusicLabAccount(arl)
                            try { client.validate() } finally { client.cancel() }
                        }
                        mutable.value = mutable.value.copy(account = account.name, message = "Signed in. Choose a track to play.")
                        Log.i("JA_MUSIC_LAB", "SESSION_VALID licensePresent=${account.license.isNotBlank()}")
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { mutable.value = mutable.value.copy(message = "Session result: ${(e as? JaMusicLabFailure)?.reason ?: "SESSION_LOCAL_ERROR"}") }
                    finally { mutable.value = mutable.value.copy(busy = false) }
                }
            }
            "SEARCH" -> {
                searchJob?.cancel()
                mutable.value = mutable.value.copy(busy = true, message = "Finding music…")
                searchJob = scope.launch {
                    try {
                        val tracks = withContext(Dispatchers.IO) { JaMusicCatalog.tracks(intent.getStringExtra("query").orEmpty()) }
                        if (tracks.isNotEmpty()) {
                            player.setMediaItems(tracks.map { mediaItem(it) }); player.prepare(); player.playWhenReady = !videoActive
                        } else mutable.value = mutable.value.copy(message = "No matching tracks found.")
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { mutable.value = mutable.value.copy(message = "Music search unavailable. Try again.") }
                    finally { mutable.value = mutable.value.copy(busy = false) }
                }
            }
            "QUEUE" -> {
                searchJob?.cancel()
                val ids = intent.getStringArrayListExtra("queue").orEmpty().filter { parseTrack(it) == it }.take(500)
                if (ids.isNotEmpty()) {
                    player.setMediaItems(ids.map { mediaItem(JaMusicCatalog.known(it)) }, intent.getIntExtra("index", 0).coerceIn(ids.indices), 0)
                    player.prepare(); player.playWhenReady = !videoActive
                }
            }
        }
        return result
    }
    private inner class LibraryCallbacks : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (!controller.isTrusted && controller.uid != applicationInfo.uid) return MediaSession.ConnectionResult.reject()
            return super.onConnect(session, controller)
        }
        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?) =
            Futures.immediateFuture(LibraryResult.ofItem(folder("root", "JA Music"), params))
        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(when (mediaId) {
                "root" -> LibraryResult.ofItem(folder("root", "JA Music"), null)
                "charts" -> LibraryResult.ofItem(folder("charts", "Top Tracks"), null)
                "queue" -> LibraryResult.ofItem(folder("queue", "Queue"), null)
                "liked" -> LibraryResult.ofItem(folder("liked", "Liked Songs"), null)
                "playlists" -> LibraryResult.ofItem(folder("playlists", "JA Playlists"), null)
                else -> if (mediaId.startsWith("local:")) {
                    val playlist = JaMusicLibrary(this@JaMusicLabService).playlists().firstOrNull { it.id == mediaId.removePrefix("local:") }
                    if (playlist != null) LibraryResult.ofItem(folder(mediaId, playlist.name), null) else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                } else if (parseTrack(mediaId) == mediaId) LibraryResult.ofItem(mediaItem(JaMusicCatalog.known(mediaId)), null)
                    else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            })
        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int,
            pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (parentId == "root") return Futures.immediateFuture(LibraryResult.ofItemList(listOf(folder("charts", "Top Tracks"), folder("liked", "Liked Songs"), folder("playlists", "JA Playlists"), folder("queue", "Queue")), params))
            val local = JaMusicLibrary(this@JaMusicLabService)
            if (parentId == "liked") return Futures.immediateFuture(LibraryResult.ofItemList(local.liked().map { JaMusicCatalog.remember(listOf(it)); mediaItem(it) }, params))
            if (parentId == "playlists") return Futures.immediateFuture(LibraryResult.ofItemList(local.playlists().map { folder("local:${it.id}", it.name) }, params))
            if (parentId.startsWith("local:")) return Futures.immediateFuture(LibraryResult.ofItemList(local.playlists().firstOrNull { it.id == parentId.removePrefix("local:") }?.tracks.orEmpty().map { JaMusicCatalog.remember(listOf(it)); mediaItem(it) }, params))
            if (parentId == "queue") return Futures.immediateFuture(LibraryResult.ofItemList(mutable.value.queue.map { mediaItem(it) }, params))
            return catalogResult("", params)
        }
        override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> {
            val query = mediaItems.firstOrNull()?.requestMetadata?.searchQuery
            if (!query.isNullOrBlank()) {
                val future = SettableFuture.create<List<MediaItem>>()
                scope.launch {
                    try { future.set(withContext(Dispatchers.IO) { JaMusicCatalog.tracks(query) }.map { mediaItem(it) }) }
                    catch (e: CancellationException) { future.cancel(false) }
                    catch (_: Exception) { future.setException(IllegalStateException("Music search unavailable")) }
                }
                return future
            }
            val items = mediaItems.take(500).mapNotNull { item -> parseTrack(item.mediaId)?.let { mediaItem(JaMusicCatalog.known(it)) } }
            return Futures.immediateFuture(items)
        }
        override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> {
            scope.launch {
                val count = withContext(Dispatchers.IO) { runCatching { JaMusicCatalog.tracks(query).size }.getOrDefault(0) }
                session.notifySearchResultChanged(browser, query, count, params)
            }
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }
        override fun onGetSearchResult(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int,
            pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = catalogResult(query, params)
    }
    private fun catalogResult(query: String, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
        scope.launch {
            try {
                val tracks = withContext(Dispatchers.IO) { JaMusicCatalog.tracks(query) }
                future.set(LibraryResult.ofItemList(tracks.map { mediaItem(it) }, params))
            } catch (_: CancellationException) { future.cancel(false) }
            catch (_: Exception) { future.set(LibraryResult.ofError(SessionError.ERROR_IO)) }
        }
        return future
    }
    override fun onDestroy() {
        connectJob?.cancel(); scope.cancel(); session?.release(); session = null
        if (::player.isInitialized) player.release()
        if (::cache.isInitialized) cache.release()
        if (clearCacheOnDestroy) File(cacheDir, "music-lab").deleteRecursively()
        if (instance === this) instance = null
        mutable.value = mutable.value.copy(running = false, busy = false, playing = false)
        super.onDestroy()
    }
}
