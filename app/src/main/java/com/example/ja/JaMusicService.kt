package com.example.ja

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession as AndroidMediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.mozilla.geckoview.*

/** A service owns the browser, never an Activity/View. No private Deezer APIs or credential extraction. */
class JaMusicService : Service() {
    companion object {
        private const val CHANNEL = "ja-deezer-music"
        private const val NOTICE = 218
        private const val PROFILE = "ja-deezer-music"
        const val OPEN_MUSIC = "ja.open.music"
        const val HOME = "https://www.deezer.com/en/"
        const val LOGIN = "https://account.deezer.com/en-us/login/"
        private const val OPEN = "ja.music.OPEN"
        private const val PLAY = "ja.music.PLAY"
        private const val PAUSE = "ja.music.PAUSE"
        private const val STOP = "ja.music.STOP"
        private const val PREVIOUS = "ja.music.PREVIOUS"
        private const val NEXT = "ja.music.NEXT"
        private var instance: JaMusicService? = null
        private var videoActive = false
        data class State(
            val session: GeckoSession? = null, val running: Boolean = false,
            val playing: Boolean = false, val loading: Boolean = false,
            val canGoBack: Boolean = false, val host: String = "",
            val title: String = "Deezer", val message: String? = null
        )
        private val mutable = MutableStateFlow(State())
        val state = mutable.asStateFlow()
        fun open(context: Context, url: String = HOME) {
            if (!JaMusicRules.allowed(url)) return
            val active = instance
            if (active != null) active.navigate(url)
            else ContextCompat.startForegroundService(context,
                Intent(context, JaMusicService::class.java).setAction(OPEN).putExtra("url", url))
        }
        fun stop(context: Context) { context.stopService(Intent(context, JaMusicService::class.java)) }
        fun playPause() { instance?.let { if (mutable.value.playing) it.pause() else it.play() } }
        fun back(): Boolean = instance?.backPage() ?: false
        fun reload() { instance?.current()?.reload() }
        fun setVideoActive(active: Boolean) {
            videoActive = active
            if (active) instance?.pause()
        }
        fun keepsProcessAlive(): Boolean = instance != null
        fun resetLogin() {
            val service = instance ?: return
            service.closePages()
            JaGeckoRuntime.get(service).storageController.clearDataForSessionContext(PROFILE)
            service.createRoot()
        }
    }

    private val pages = mutableListOf<GeckoSession>()
    private val canBack = mutableMapOf<GeckoSession, Boolean>()
    private var media: org.mozilla.geckoview.MediaSession? = null
    private lateinit var controls: AndroidMediaSession
    private lateinit var audio: AudioManager
    private lateinit var focus: AudioFocusRequest
    private var destroying = false
    private var receiverRegistered = false
    private var desiredUrl = HOME
    private var prepared = false
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { pause() }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "JA Music", NotificationManager.IMPORTANCE_LOW))
        audio = getSystemService(AudioManager::class.java)
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { change ->
                if (change != AudioManager.AUDIOFOCUS_GAIN) pause()
            }.build()
        controls = AndroidMediaSession(this, "JA-Deezer").apply {
            setCallback(object : AndroidMediaSession.Callback() {
                override fun onPlay() { play() }
                override fun onPause() { pause() }
                override fun onStop() { stopSelf() }
                override fun onSkipToNext() { media?.nextTrack() }
                override fun onSkipToPrevious() { media?.previousTrack() }
            })
            isActive = true
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(noisy,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        receiverRegistered = true
        mutable.value = State(running = true, loading = true, message = "Preparing Deezer…")
        updateNotification()
        createRoot()
        val runtime = JaGeckoRuntime.get(this)
        JaGeckoAdBlockers.prepare(runtime, PlayerControlSettings(this).adBlockerMode) {
            if (instance !== this) return@prepare
            prepared = true
            current()?.loadUri(desiredUrl)
        }
        Log.i("JA_MUSIC", "SERVICE_CREATED")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            OPEN -> navigate(intent.getStringExtra("url") ?: HOME)
            PLAY -> play()
            PAUSE -> pause()
            NEXT -> media?.nextTrack()
            PREVIOUS -> media?.previousTrack()
            STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null

    private fun current(): GeckoSession? = pages.lastOrNull()
    private fun publishPage() {
        val session = current()
        mutable.value = mutable.value.copy(session = session,
            canGoBack = pages.size > 1 || canBack[session] == true)
    }
    private fun createRoot() {
        pages.add(newPage())
        publishPage()
        if (prepared) current()?.loadUri(HOME)
    }
    private fun newPage(open: Boolean = true): GeckoSession {
        val page = GeckoSession(GeckoSessionSettings.Builder().contextId(PROFILE)
            .suspendMediaWhenInactive(false).build())
        page.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
                if (JaMusicRules.allowed(request.uri)) return null
                mutable.value = mutable.value.copy(message = "Music only opens secure HTTPS web links.")
                val target = android.net.Uri.parse(request.uri)
                Log.i("JA_MUSIC", "NAVIGATION_BLOCKED scheme=${target.scheme.orEmpty()} " +
                    "host=${target.host.orEmpty()} redirect=${request.isRedirect}")
                // Never log the path, query, fragment, tokens or passwords.
                return GeckoResult.fromValue(AllowOrDeny.DENY)
            }
            override fun onLocationChange(session: GeckoSession, url: String?, perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
                if (session === current()) {
                    mutable.value = mutable.value.copy(host = android.net.Uri.parse(url.orEmpty()).host.orEmpty())
                    publishPage()
                }
            }
            override fun onCanGoBack(session: GeckoSession, value: Boolean) {
                canBack[session] = value
                if (session === current()) publishPage()
            }
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                if (!JaMusicRules.allowed(uri) || pages.size >= 3) return null
                val child = newPage(open = false)
                pages.add(child)
                return GeckoResult.fromValue(child)
            }
            override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? {
                mutable.value = mutable.value.copy(loading = false,
                    message = "Deezer could not load. Check your connection, then Reload.")
                Log.w("JA_MUSIC", "PAGE_LOAD_ERROR code=${error.code}")
                return null
            }
        }
        page.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                if (session === current()) {
                    publishPage()
                    mutable.value = mutable.value.copy(loading = true, message = null)
                }
            }
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (session === current()) mutable.value = mutable.value.copy(loading = false,
                    message = if (success) null else "Deezer did not finish loading. Try Reload.")
            }
        }
        page.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onCloseRequest(session: GeckoSession) {
                if (session === current() && pages.size > 1) {
                    pages.removeAt(pages.lastIndex).close()
                    canBack.remove(session)
                    publishPage()
                }
            }
            override fun onCrash(session: GeckoSession) {
                mutable.value = mutable.value.copy(playing = false, loading = false,
                    message = "Deezer's browser stopped. Stop Music, then reopen it.")
                updateNotification()
            }
        }
        page.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onContentPermissionRequest(session: GeckoSession,
                perm: GeckoSession.PermissionDelegate.ContentPermission): GeckoResult<Int>? {
                return when (perm.permission) {
                    GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE,
                    GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE ->
                        GeckoResult.fromValue(if (videoActive)
                            GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                            else GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
                    GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS -> {
                        val host = android.net.Uri.parse(perm.uri).host.orEmpty()
                        GeckoResult.fromValue(if (host == "deezer.com" || host.endsWith(".deezer.com"))
                            GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                            else GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
                    }
                    else -> null
                }
            }
        }
        page.setMediaSessionDelegate(object : org.mozilla.geckoview.MediaSession.Delegate {
            override fun onActivated(session: GeckoSession, mediaSession: org.mozilla.geckoview.MediaSession) {
                if (!destroying) media = mediaSession
            }
            override fun onDeactivated(session: GeckoSession, mediaSession: org.mozilla.geckoview.MediaSession) {
                if (destroying) return
                if (media === mediaSession) { media = null; mutable.value = mutable.value.copy(playing = false); updateNotification() }
            }
            override fun onPlay(session: GeckoSession, mediaSession: org.mozilla.geckoview.MediaSession) {
                if (destroying) return
                media = mediaSession
                val granted = !videoActive && audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                if (!JaMusicRules.mayPlay(videoActive, granted)) { pause(); return }
                mutable.value = mutable.value.copy(playing = true)
                updateNotification()
                Log.i("JA_MUSIC", "PLAYING")
            }
            override fun onPause(session: GeckoSession, mediaSession: org.mozilla.geckoview.MediaSession) {
                if (destroying) return
                mutable.value = mutable.value.copy(playing = false)
                audio.abandonAudioFocusRequest(focus)
                updateNotification()
            }
            override fun onMetadata(session: GeckoSession, mediaSession: org.mozilla.geckoview.MediaSession,
                meta: org.mozilla.geckoview.MediaSession.Metadata) {
                if (destroying) return
                mutable.value = mutable.value.copy(title = meta.title?.takeIf { it.isNotBlank() } ?: "Deezer")
                controls.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, mutable.value.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, meta.artist.orEmpty()).build())
                updateNotification()
            }
        })
        if (open) page.open(JaGeckoRuntime.get(this))
        return page
    }
    private fun navigate(url: String) {
        if (!JaMusicRules.allowed(url)) return
        desiredUrl = url
        if (prepared) current()?.loadUri(url)
    }
    private fun backPage(): Boolean {
        val page = current() ?: return false
        if (canBack[page] == true) { page.goBack(); return true }
        if (pages.size > 1) {
            pages.removeAt(pages.lastIndex).close(); canBack.remove(page); publishPage(); return true
        }
        return false
    }
    private fun play() {
        if (videoActive) return
        media?.play()
    }
    private fun pause() {
        media?.pause()
        mutable.value = mutable.value.copy(playing = false)
        audio.abandonAudioFocusRequest(focus)
        updateNotification()
    }
    private fun pending(action: String, code: Int): PendingIntent =
        PendingIntent.getService(this, code, Intent(this, JaMusicService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun updateNotification() {
        if (destroying) return
        val playing = mutable.value.playing
        controls.setPlaybackState(PlaybackState.Builder().setActions(
            PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (playing) 1f else 0f).build())
        val open = PendingIntent.getActivity(this, 218, Intent(this, MainActivity::class.java)
            .putExtra(OPEN_MUSIC, true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notice = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(mutable.value.title).setContentText(if (playing) "Deezer • Playing" else "Deezer • Ready / paused")
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(playing)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_previous, "Previous", pending(PREVIOUS, 1)).build())
            .addAction(Notification.Action.Builder(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pause" else "Play", pending(if (playing) PAUSE else PLAY, 2)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_next, "Next", pending(NEXT, 3)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Stop Music", pending(STOP, 4)).build())
            .setStyle(Notification.MediaStyle().setMediaSession(controls.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTICE, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTICE, notice)
    }
    private fun closePages() {
        media?.pause(); media = null
        pages.forEach { it.close() }; pages.clear(); canBack.clear()
        mutable.value = mutable.value.copy(session = null, playing = false)
    }
    override fun onDestroy() {
        destroying = true
        instance = null
        closePages()
        if (receiverRegistered) unregisterReceiver(noisy)
        audio.abandonAudioFocusRequest(focus)
        controls.isActive = false; controls.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        mutable.value = State()
        Log.i("JA_MUSIC", "SERVICE_DESTROYED")
        super.onDestroy()
    }
}
