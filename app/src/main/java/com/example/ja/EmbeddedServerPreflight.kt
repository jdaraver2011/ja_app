package com.example.ja

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * On-demand HTTP preflight for deferred fallback diagnostics only.
 * The details screen and initial Play action do not start this scan.
 * Only VidSrc (.sh), VidFast (.vc), and CineJoy (.pk) are probed.
 * IMPORTANT: A 2xx embed response means only that the page responded. It does
 * not establish that the actual movie or TV episode can play. Playability is
 * checked inside GeckoView, where the existing explicit-error fallback remains.
 */
data class ServerScanResult(
    val ranked: List<PlaybackChoice>,
    val checked: Int,
    val responding: Int,
    val usedImdbId: Boolean,
    /** For playback preference scoring. A 2xx is NOT proof that a video plays. */
    val responseCodes: Map<String, Int> = emptyMap()
)

class EmbeddedServerPreflight(
    private val registry: PlaybackRegistry = PlaybackRegistry(),
    private val domainLedger: ServerDomainLedger? = null
) {
    private val http = OkHttpClient.Builder()
        // An embed request must never follow an untrusted ad-domain redirect.
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(2200, TimeUnit.MILLISECONDS)
        .readTimeout(2200, TimeUnit.MILLISECONDS)
        .callTimeout(4200, TimeUnit.MILLISECONDS)
        .build()
    private val lock = Any()
    private data class Cached(val atMs: Long, val result: ServerScanResult)
    private val cache = mutableMapOf<String, Cached>()
    private val inflight = mutableMapOf<String, Deferred<ServerScanResult>>()
    private val perTitleTtlMs = 3 * 60 * 1000L
    private val networkSlots = Semaphore(3)

    private fun keyOf(request: PlaybackRequest) =
        "${request.media.type}:${request.media.id}:${request.season}:${request.episode}"

    fun start(scope: CoroutineScope, request: PlaybackRequest): Deferred<ServerScanResult> {
        val key = keyOf(request)
        synchronized(lock) {
            cache[key]?.takeIf { System.currentTimeMillis() - it.atMs < perTitleTtlMs }?.let {
                return CompletableDeferred(it.result)
            }
            inflight[key]?.let { return it }
            val deferred = scope.async {
                val result = scan(request)
                synchronized(lock) { cache[key] = Cached(System.currentTimeMillis(), result) }
                result
            }
            inflight[key] = deferred
            deferred.invokeOnCompletion {
                synchronized(lock) { if (inflight[key] === deferred) inflight.remove(key) }
            }
            return deferred
        }
    }

    private data class Probe(val choice: PlaybackChoice, val index: Int, val responseCode: Int, val durationMs: Long) {
        // 2xx = reachable embed page; 3xx/4xx/5xx cannot confirm playable media.
        val responsive get() = responseCode in 200..299
        val sortGroup get() = when {
            responsive -> 0
            responseCode in 300..399 || responseCode == 403 || responseCode == 429 -> 1
            else -> 2
        }
    }

    private suspend fun probe(
        choice: PlaybackChoice, index: Int, allChoices: List<PlaybackChoice>
    ): Probe = networkSlots.withPermit {
        withContext(Dispatchers.IO) {
            val url = when (val source = choice.source) {
                is PlaybackSource.Native -> source.url
                is PlaybackSource.Embed -> source.url
            }
            val begin = System.nanoTime()
            val code = try {
                // No player is launched. Range limits bandwidth where supported;
                // the response is closed immediately after its status is read.
                val request = Request.Builder().url(url)
                    .header("Range", "bytes=0-2047")
                    .header("Accept", "text/html,application/xhtml+xml,*/*")
                    .get().build()
                http.newCall(request).execute().use { response ->
                    if (response.code in setOf(301, 302, 307, 308)) {
                        // Record only an actual provider HTTP redirect, never a
                        // navigation to an advertising iframe or streaming CDN.
                        val resolved = response.header("Location")?.let { destination ->
                            try { response.request.url.resolve(destination)?.toString() } catch (_: Exception) { null }
                        }
                        domainLedger?.observeHttpRedirect(choice, response.code, resolved, allChoices)
                    }
                    response.code
                }
            } catch (_: Exception) { 0 }
            val elapsed = (System.nanoTime() - begin) / 1_000_000L
            Log.d("JA_SERVERS", "PROBE id=${choice.id} http=$code ms=$elapsed")
            Probe(choice, index, code, elapsed)
        }
    }

    private suspend fun scan(request: PlaybackRequest): ServerScanResult {
        // All three supported providers use TMDB IDs. Do not request IMDb IDs
        // from TMDB or try removed source adapters.
        val options = registry.choices(request)
        if (options.isEmpty()) return ServerScanResult(emptyList(), 0, 0, false)
        val results = supervisorScope {
            options.mapIndexed { idx, candidate -> async { probe(candidate, idx, options) } }.awaitAll()
        }
        // A fast HTML or anti-bot page is not necessarily a working player.
        // Prefer the known Gecko-compatible providers over raw response speed;
        // keep nonresponders as last-resort candidates for explicit failover.
        val rankedByHttp = results.sortedWith(compareBy<Probe> { it.sortGroup }
            .thenBy { ServerPlaybackHistory.defaultPriority(it.choice.id) }
            .thenBy { it.durationMs }
            .thenBy { it.index }).map { it.choice }
        // A historical domain-ledger entry must never revive a deleted source.
        val enabledIds = options.mapTo(HashSet()) { it.id }
        val ranked = (domainLedger?.apply(rankedByHttp, includePending = true) ?: rankedByHttp)
            .filter { it.id in enabledIds }
        val responsive = results.count { it.responsive }
        Log.i("JA_SERVERS", "SCAN_DONE type=${request.media.type} tmdb=${request.media.id} " +
            "s=${request.season} e=${request.episode} checked=${results.size} reachable=$responsive " +
            "imdb=false first=${ranked.firstOrNull()?.id ?: "none"}")
        return ServerScanResult(ranked, results.size, responsive, false,
            results.associate { it.choice.id to it.responseCode })
    }
}
