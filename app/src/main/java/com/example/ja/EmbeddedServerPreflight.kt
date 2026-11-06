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
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Quick, bounded HTTP preflight that starts while title details are displayed.
 * IMPORTANT: A 2xx embed response means only that the page responded. It does
 * not establish that the actual movie or TV episode can play. Playability is
 * checked inside GeckoView, where the existing explicit-error fallback remains.
 */
data class ServerScanResult(
    val ranked: List<PlaybackChoice>,
    val checked: Int,
    val responding: Int,
    val usedImdbId: Boolean
)

class EmbeddedServerPreflight(private val registry: PlaybackRegistry = PlaybackRegistry()) {
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
    private val imdbCache = mutableMapOf<String, String?>()
    private val perTitleTtlMs = 3 * 60 * 1000L
    private val networkSlots = Semaphore(13)

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

    private suspend fun imdbFor(request: PlaybackRequest): String? {
        if (request.imdbId?.matches(Regex("tt[0-9]+")) == true) return request.imdbId
        val titleKey = "${request.media.type}:${request.media.id}"
        synchronized(lock) { if (imdbCache.containsKey(titleKey)) return imdbCache[titleKey] }
        val id = withContext(Dispatchers.IO) {
            try {
                val url = "https://api.themoviedb.org/3/${request.media.type}/${request.media.id}/external_ids" +
                    "?api_key=${BuildConfig.TMDB_API_KEY}"
                if (BuildConfig.TMDB_API_KEY.isBlank()) return@withContext null
                val httpRequest = Request.Builder().url(url).get().build()
                http.newCall(httpRequest).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val json = JSONObject(response.body?.string().orEmpty())
                    json.optString("imdb_id").takeIf { it.matches(Regex("tt[0-9]+")) }
                }
            } catch (e: Exception) {
                Log.w("JA_SERVERS", "TMDB_EXTERNAL_ID_FAILED type=${request.media.type}", e)
                null
            }
        }
        synchronized(lock) { imdbCache[titleKey] = id }
        return id
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

    private suspend fun probe(choice: PlaybackChoice, index: Int): Probe = networkSlots.withPermit {
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
                http.newCall(request).execute().use { it.code }
            } catch (_: Exception) { 0 }
            val elapsed = (System.nanoTime() - begin) / 1_000_000L
            Log.d("JA_SERVERS", "PROBE id=${choice.id} http=$code ms=$elapsed")
            Probe(choice, index, code, elapsed)
        }
    }

    private suspend fun scan(request: PlaybackRequest): ServerScanResult {
        val imdb = imdbFor(request)
        val options = registry.choices(request.copy(imdbId = imdb))
        if (options.isEmpty()) return ServerScanResult(emptyList(), 0, 0, imdb != null)
        val results = supervisorScope {
            options.mapIndexed { idx, candidate -> async { probe(candidate, idx) } }.awaitAll()
        }
        // JA has working on-device playback history with the original direct,
        // VidSrc and VidFast providers. A random HTTP 200 page from an untested
        // site must NOT replace them (the 111Movies blank-screen regression).
        // Their order is intentionally stable even if a bot-blocked HTTP probe
        // returns 403: GeckoView may still load the embed successfully.
        val knownProviderIds = setOf("licensed-direct", "vidsrc", "vidfast-vc")
        val known = results.filter { it.choice.id in knownProviderIds }
            .sortedBy { it.index }
        // Only compare response times among experimental providers. They stay
        // in the automatic fallback list but cannot be promoted by page ping.
        val unverified = results.filter { it.choice.id !in knownProviderIds }
            .sortedWith(compareBy<Probe> { it.sortGroup }
                .thenBy { if (it.responsive) it.durationMs else Long.MAX_VALUE }
                .thenBy { it.index })
        val ranked = (known + unverified).map { it.choice }
        val responsive = results.count { it.responsive }
        Log.i("JA_SERVERS", "SCAN_DONE type=${request.media.type} tmdb=${request.media.id} " +
            "s=${request.season} e=${request.episode} checked=${results.size} reachable=$responsive " +
            "imdb=${imdb != null} first=${ranked.firstOrNull()?.id ?: "none"} " +
            "experimental=${unverified.size}")
        return ServerScanResult(ranked, results.size, responsive, imdb != null)
    }
}
