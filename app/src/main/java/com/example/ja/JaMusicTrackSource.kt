package com.example.ja

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/** Resolves only account-issued FULL URLs on ExoPlayer's loader thread, never on the UI thread. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class JaMusicTrackSource(private val context: Context, private val upstream: DataSource.Factory,
    private val onTrack: (JaMusicLabAccount.Account, JaMusicLabAccount.Track) -> Unit) : DataSource {
    private var delegate: DataSource? = null
    private var client: JaMusicLabAccount? = null
    private val listeners = mutableListOf<TransferListener>()
    override fun addTransferListener(listener: TransferListener) { listeners.add(listener); delegate?.addTransferListener(listener) }
    override fun getUri(): Uri? = delegate?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders.orEmpty()
    override fun open(spec: DataSpec): Long {
        try {
            if (spec.uri.scheme != "ja-deezer" || spec.uri.host != "track") throw JaMusicLabFailure("TRACK_URI_INVALID")
            val id = spec.uri.lastPathSegment?.let { JaMusicLabService.parseTrack(it) } ?: throw JaMusicLabFailure("TRACK_ID_INVALID")
            val arl = JaMusicLabVault(context).read() ?: throw JaMusicLabFailure("SESSION_REQUIRED")
            val accountClient = JaMusicLabAccount(arl); client = accountClient
            val account = accountClient.validate()
            val track = accountClient.track(id, account)
            val audio = accountClient.audio(track, account)
            onTrack(account, track)
            val factory = if (audio.cipher == "BF_CBC_STRIPE")
                JaDeezerDataSource.Factory(upstream, JaDeezerStripeInputStream.trackKey(track.id)) else upstream
            val source = factory.createDataSource(); delegate = source
            listeners.forEach { source.addTransferListener(it) }
            return source.open(spec.buildUpon().setUri(audio.url)
                .setKey("${account.id}:${track.id}:${audio.format}:${audio.cipher}").build())
        } catch (e: Exception) {
            close()
            // URLs, cookies, license tokens and low-level request exception messages stay private.
            throw java.io.IOException((e as? JaMusicLabFailure)?.reason ?: "AUDIO_NETWORK_OR_DECODER")
        }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = delegate?.read(buffer, offset, length)
        ?: throw java.io.IOException("AUDIO_NOT_OPEN")
    override fun close() { try { delegate?.close() } finally { delegate = null; client?.cancel(); client = null } }
    class Factory(private val context: Context, private val upstream: DataSource.Factory,
        private val onTrack: (JaMusicLabAccount.Account, JaMusicLabAccount.Track) -> Unit) : DataSource.Factory {
        override fun createDataSource(): DataSource = JaMusicTrackSource(context, upstream, onTrack)
    }
}
