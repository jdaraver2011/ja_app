package com.example.ja

import android.app.*
import android.content.*
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class JaMusicLabService : Service() {
    companion object {
        private const val CHANNEL = "ja-music-lab"
        private const val NOTICE = 245
        private var instance: JaMusicLabService? = null
        private var videoActive = false
        data class State(val running: Boolean = false, val busy: Boolean = false,
            val message: String = "Connect your account to test native playback.", val account: String = "",
            val title: String = "", val playing: Boolean = false, val position: Long = 0, val duration: Long = 0)
        private val mutable = MutableStateFlow(State())
        val state = mutable.asStateFlow()
        fun connect(context: Context, arl: String) {
            val value = arl.trim()
            if (!Regex("[A-Za-z0-9_-]{64,512}").matches(value)) {
                mutable.value = mutable.value.copy(message = "Enter a valid ARL session value."); return
            }
            try { JaMusicLabVault(context).save(value) }
            catch (_: Exception) { mutable.value = mutable.value.copy(message = "Could not securely store this session."); return }
            launch(context, "CONNECT")
        }
        fun restore(context: Context) = launch(context, "CONNECT")
        fun playTrack(context: Context, input: String) {
            val id = parseTrack(input)
            if (id == null) { mutable.value = mutable.value.copy(message = "Enter a Deezer track ID or https://www.deezer.com/…/track/ID link."); return }
            JaMusicService.stop(context)
            ContextCompat.startForegroundService(context, Intent(context, JaMusicLabService::class.java)
                .setAction("TRACK").putExtra("track", id))
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
            ContextCompat.startForegroundService(context, Intent(context, JaMusicLabService::class.java).setAction(action))
        }
        fun playPause() { instance?.let { if (it.player.isPlaying) it.player.pause() else if (!videoActive) it.player.play() } }
        fun seek(delta: Long) { instance?.let { it.player.seekTo((it.player.currentPosition + delta).coerceAtLeast(0)) } }
        fun stop(context: Context) {
            instance?.cancelPending()
            context.stopService(Intent(context, JaMusicLabService::class.java))
        }
        fun logout(context: Context) {
            JaMusicLabVault(context).clear()
            instance?.let { it.cancelPending(); it.clearCacheOnDestroy = true; it.stopSelf() }
                ?: File(context.cacheDir, "music-lab").deleteRecursively()
            mutable.value = State(message = "Signed out. Local session and audio cache cleared.")
        }
        fun setVideoActive(active: Boolean) { videoActive = active; if (active) instance?.player?.pause() }
        fun keepsProcessAlive(): Boolean = instance != null
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var request: Job? = null
    private var accountClient: JaMusicLabAccount? = null
    private lateinit var player: ExoPlayer
    private lateinit var controls: MediaSession
    private lateinit var cache: SimpleCache
    private var clearCacheOnDestroy = false
    private var generation = 0L
    private var expectedDuration = 0L
    private fun cancelPending() {
        ++generation; request?.cancel(); accountClient?.cancel()
        if (::player.isInitialized) { player.stop(); player.clearMediaItems() }
        mutable.value = mutable.value.copy(busy = false, playing = false, duration = 0, position = 0)
    }
    override fun onCreate() {
        super.onCreate(); instance = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "JA Music Lab", NotificationManager.IMPORTANCE_LOW))
        controls = MediaSession(this, "JA-Music-Lab").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { if (!videoActive) player.play() }
                override fun onPause() { player.pause() }
                override fun onSeekTo(pos: Long) { player.seekTo(pos.coerceAtLeast(0)) }
                override fun onStop() { cancelPending(); stopSelf() }
            }); isActive = true
        }
        mutable.value = mutable.value.copy(running = true)
        notification()
        cache = SimpleCache(File(cacheDir, "music-lab"), LeastRecentlyUsedCacheEvictor(64L * 1024 * 1024), StandaloneDatabaseProvider(this))
        val source = CacheDataSource.Factory().setCache(cache)
            .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory().setUserAgent("JA-MusicLab")
                .setAllowCrossProtocolRedirects(false))
        player = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(source)).build().apply {
            setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
            addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    mutable.value = mutable.value.copy(playing = player.isPlaying,
                        position = player.currentPosition.coerceAtLeast(0), duration = player.duration.coerceAtLeast(0))
                    notification()
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    val event = when (playbackState) {
                        Player.STATE_BUFFERING -> "BUFFERING"
                        Player.STATE_READY -> "READY"
                        Player.STATE_ENDED -> "ENDED"
                        else -> "IDLE"
                    }
                    Log.i("JA_MUSIC_LAB", "PLAYER_$event")
                    if (playbackState == Player.STATE_READY && player.duration > 0 &&
                        expectedDuration > 45000 && player.duration < expectedDuration - 10000) {
                        cancelPending()
                        mutable.value = mutable.value.copy(message = "Returned audio is shorter than the complete track; stopped.")
                        Log.i("JA_MUSIC_LAB", "SHORT_AUDIO_REJECTED")
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    val code = error.errorCodeName
                    mutable.value = mutable.value.copy(message = "Playback failed: $code", playing = false)
                    Log.i("JA_MUSIC_LAB", "PLAYER_ERROR code=$code")
                }
            })
        }
        scope.launch { while (isActive) { delay(1000)
            mutable.value = mutable.value.copy(position = player.currentPosition.coerceAtLeast(0))
        } }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "PAUSE" -> player.pause()
            "PLAY" -> if (!videoActive) player.play()
            "STOP" -> { cancelPending(); stopSelf() }
            "CONNECT", "TRACK" -> resolve(intent.getStringExtra("track"))
        }
        return START_NOT_STICKY
    }
    private fun resolve(trackId: String?) {
        request?.cancel(); accountClient?.cancel(); player.stop(); player.clearMediaItems()
        val sequence = ++generation
        mutable.value = mutable.value.copy(busy = true, title = "", message = "Validating account…", duration = 0, position = 0)
        request = scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { JaMusicLabVault(this@JaMusicLabService).read() }
                    ?: throw JaMusicLabFailure("SESSION_REQUIRED")
                val client = JaMusicLabAccount(saved); accountClient = client
                val account = withContext(Dispatchers.IO) { client.validate() }
                ensureActive()
                mutable.value = mutable.value.copy(account = account.name, message = "Account validated.")
                Log.i("JA_MUSIC_LAB", "SESSION_VALID licensePresent=${account.license.isNotBlank()}")
                if (trackId != null) {
                    mutable.value = mutable.value.copy(message = "Looking up track…")
                    val track = withContext(Dispatchers.IO) { client.track(trackId, account) }
                    Log.i("JA_MUSIC_LAB", "TRACK_FOUND id=${track.id} expectedMs=${track.duration}")
                    mutable.value = mutable.value.copy(title = "${track.title} • ${track.artist}", message = "Requesting full-track audio…")
                    val audio = withContext(Dispatchers.IO) { client.audio(track, account) }
                    ensureActive()
                    if (sequence != generation) return@launch
                    expectedDuration = track.duration
                    player.setMediaItem(MediaItem.Builder().setUri(audio.url).setMediaId(track.id)
                        .setCustomCacheKey("${account.id}:${track.id}:${audio.format}").build())
                    player.prepare(); player.playWhenReady = !videoActive
                    mutable.value = mutable.value.copy(message = "Full-track source returned • ${audio.format}")
                    Log.i("JA_MUSIC_LAB", "AUDIO_RESOLVED format=${audio.format} autoplay=${!videoActive}")
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val reason = (e as? JaMusicLabFailure)?.reason ?: "LOCAL_OPERATION_FAILED"
                if (sequence == generation) {
                    mutable.value = mutable.value.copy(message = "Test result: $reason. No preview fallback was used.")
                    Log.i("JA_MUSIC_LAB", "TEST_FAILED reason=$reason")
                }
            } finally { if (sequence == generation) mutable.value = mutable.value.copy(busy = false) }
        }
    }
    private fun notification() {
        val state = mutable.value
        fun action(value: String): PendingIntent = PendingIntent.getService(this, value.hashCode(),
            Intent(this, JaMusicLabService::class.java).setAction(value), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 245, Intent(this, MainActivity::class.java)
            .putExtra(JaMusicService.OPEN_MUSIC, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        controls.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,
            state.title.ifBlank { "JA Music Lab" }).build())
        controls.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_STOP or PlaybackState.ACTION_SEEK_TO)
            .setState(if (state.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, state.position, if (state.playing) 1f else 0f).build())
        val notice = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(state.title.ifBlank { "JA Music Lab" }).setContentText("Experimental native playback")
            .setContentIntent(open).setOngoing(state.playing)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, if (state.playing) "Pause" else "Play", action(if (state.playing) "PAUSE" else "PLAY")).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Stop", action("STOP")).build())
            .setStyle(Notification.MediaStyle().setMediaSession(controls.sessionToken).setShowActionsInCompactView(0, 1)).build()
        startForeground(NOTICE, notice)
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        ++generation; request?.cancel(); scope.cancel(); accountClient?.cancel()
        if (::player.isInitialized) player.release()
        if (::controls.isInitialized) controls.release()
        if (::cache.isInitialized) cache.release()
        if (clearCacheOnDestroy) File(cacheDir, "music-lab").deleteRecursively()
        if (instance === this) instance = null
        mutable.value = mutable.value.copy(running = false, busy = false, playing = false)
        super.onDestroy()
    }
}
