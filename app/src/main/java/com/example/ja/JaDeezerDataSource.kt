package com.example.ja

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.EOFException
import java.io.InputStream

/** Cache sits upstream so disk contains encrypted CDN bytes, not exported songs. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class JaDeezerDataSource(private val upstream: DataSource, private val key: ByteArray) : DataSource {
    private var stream: InputStream? = null
    private var remaining = C.LENGTH_UNSET.toLong()
    override fun addTransferListener(listener: TransferListener) = upstream.addTransferListener(listener)
    override fun getUri(): Uri? = upstream.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
    override fun open(spec: DataSpec): Long {
        val aligned = spec.position / JaDeezerStripeInputStream.STRIPE * JaDeezerStripeInputStream.STRIPE
        val skip = spec.position - aligned
        // Open from a stripe boundary; a bounded caller may still need the rest of its encrypted block.
        val input = object : InputStream() {
            override fun read(): Int { val one = ByteArray(1); return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255 }
            override fun read(b: ByteArray, off: Int, len: Int): Int = upstream.read(b, off, len)
        }
        val decoded = JaDeezerStripeInputStream(input, key)
        stream = decoded
        try {
            val available = upstream.open(spec.buildUpon().setPosition(aligned).setLength(C.LENGTH_UNSET.toLong()).build())
            val discard = ByteArray(6144)
            var left = skip
            while (left > 0) {
                val n = decoded.read(discard, 0, minOf(left, discard.size.toLong()).toInt())
                if (n < 0) throw EOFException("Audio seek beyond end")
                left -= n
            }
            val actual = if (available == C.LENGTH_UNSET.toLong()) available else (available - skip).coerceAtLeast(0)
            remaining = if (spec.length == C.LENGTH_UNSET.toLong()) actual else if (actual < 0) spec.length else minOf(actual, spec.length)
            return remaining
        } catch (e: Exception) { close(); throw e }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val count = stream?.read(buffer, offset, if (remaining < 0) length else minOf(length.toLong(), remaining).toInt())
            ?: throw IllegalStateException("Audio stream not open")
        if (count > 0 && remaining > 0) remaining -= count
        return count
    }
    override fun close() { try { stream?.close() } finally { stream = null; upstream.close() } }
    class Factory(private val upstream: DataSource.Factory, private val key: ByteArray) : DataSource.Factory {
        override fun createDataSource(): DataSource = JaDeezerDataSource(upstream.createDataSource(), key)
    }
}
