package com.example.ja

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.Collator
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class TmdbNetwork(val id: Int, val name: String)
internal data class TmdbNetworkDirectory(val networks: List<TmdbNetwork>, val warning: String? = null)
internal data class TmdbNetworkRow(
    val titles: List<TitleItem>, val nextPage: Int, val totalPages: Int
) {
    val hasMore: Boolean get() = nextPage <= minOf(totalPages, 500)
}

/** On-demand metadata only. No source probes or work during player playback. */
internal class TmdbNetworksRepository(context: Context) {
    private data class SavedDirectory(val date: LocalDate, val networks: List<TmdbNetwork>)
    private val directoryFile = AtomicFile(File(context.applicationContext.filesDir, "ja-tv-networks.json"))
    private val directoryLock = Mutex()
    private val rowSlots = Semaphore(3)
    private val rows = object : LinkedHashMap<Int, TmdbNetworkRow>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, TmdbNetworkRow>?): Boolean = size > 96
    }
    private var saved: SavedDirectory? = null
    private val client = JaTmdbHttp.client

    fun cachedRow(id: Int): TmdbNetworkRow? = synchronized(rows) { rows[id] }

    private suspend fun <T> request(url: String, decode: (Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(Request.Builder().url(url).build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use { decode(it) }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }

    private fun sorted(values: List<TmdbNetwork>): List<TmdbNetwork> {
        val collator = Collator.getInstance(Locale.ENGLISH).apply { strength = Collator.PRIMARY }
        return values.distinctBy { it.id }.sortedWith(Comparator { a, b ->
            val order = collator.compare(a.name, b.name)
            if (order != 0) order else a.id.compareTo(b.id)
        })
    }

    private fun readSaved(): SavedDirectory? = try {
        val obj = JSONObject(directoryFile.openRead().bufferedReader().use { it.readText() })
        val items = obj.getJSONArray("networks")
        val values = (0 until items.length()).mapNotNull { index ->
            val item = items.getJSONObject(index)
            val id = item.optInt("id")
            val name = item.optString("name").trim()
            if (id > 0 && name.isNotBlank() && name != "null") TmdbNetwork(id, name) else null
        }
        values.takeIf { it.isNotEmpty() }?.let { SavedDirectory(LocalDate.parse(obj.getString("date")), sorted(it)) }
    } catch (_: Exception) { null }

    private fun saveDirectory(value: SavedDirectory) {
        val items = JSONArray()
        value.networks.forEach { items.put(JSONObject().put("id", it.id).put("name", it.name)) }
        val bytes = JSONObject().put("date", value.date.toString()).put("networks", items).toString().toByteArray()
        val stream = directoryFile.startWrite()
        try { stream.write(bytes); directoryFile.finishWrite(stream) }
        catch (e: Exception) { directoryFile.failWrite(stream); throw e }
    }

    suspend fun directory(force: Boolean = false): TmdbNetworkDirectory = withContext(Dispatchers.IO) {
        directoryLock.withLock {
            val now = ZonedDateTime.now(ZoneOffset.UTC)
            // TMDB finishes the export by 08:00 UTC. Leave a publication margin.
            val date = now.toLocalDate().minusDays(if (now.hour < 9) 1 else 0)
            val cached = saved ?: readSaved().also { saved = it }
            if (!force && cached != null && cached.date == date) return@withLock TmdbNetworkDirectory(cached.networks)
            try {
                var fresh: SavedDirectory? = null
                for (candidate in listOf(date, date.minusDays(1))) {
                    currentCoroutineContext().ensureActive()
                    val stamp = candidate.format(DateTimeFormatter.ofPattern("MM_dd_yyyy", Locale.US))
                    val networks = request("https://files.tmdb.org/p/exports/tv_network_ids_$stamp.json.gz") { response ->
                        if (response.code == 404) null
                        else {
                            check(response.isSuccessful) { "Could not load TV networks (HTTP ${response.code})." }
                            val body = response.body ?: error("TMDB returned an empty network directory.")
                            check(body.contentLength() <= 8L * 1024 * 1024) { "TV network directory is too large." }
                            val values = mutableListOf<TmdbNetwork>()
                            var characters = 0L
                            GZIPInputStream(body.byteStream()).bufferedReader(Charsets.UTF_8).useLines { lines ->
                                lines.forEach { line ->
                                    characters += line.length
                                    check(characters <= 16L * 1024 * 1024 && values.size < 100_000) { "TV network directory is too large." }
                                    if (line.isNotBlank()) {
                                        val item = JSONObject(line)
                                        val id = item.optInt("id")
                                        val name = item.optString("name").trim()
                                        if (id > 0 && name.isNotBlank() && name != "null") values.add(TmdbNetwork(id, name))
                                    }
                                }
                            }
                            check(values.isNotEmpty()) { "TMDB returned no named TV networks." }
                            sorted(values)
                        }
                    }
                    if (networks != null) { fresh = SavedDirectory(candidate, networks); break }
                }
                val updated = fresh ?: error("The TV network directory is not available yet. Please retry.")
                // A failed disk write should not hide successfully downloaded networks.
                val warning = try { saveDirectory(updated); null }
                    catch (_: Exception) { "Networks loaded, but could not be saved for offline browsing." }
                saved = updated
                TmdbNetworkDirectory(updated.networks, warning)
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (e: Exception) {
                if (cached != null) TmdbNetworkDirectory(cached.networks, "Using saved networks. Refresh failed; select Retry.")
                else throw e
            }
        }
    }

    suspend fun loadRow(network: TmdbNetwork, more: Boolean = false): TmdbNetworkRow = rowSlots.withPermit {
        require(network.id > 0)
        val cached = cachedRow(network.id)
        if (!more && cached != null) return@withPermit cached
        if (cached != null && !cached.hasMore) return@withPermit cached
        var row = cached
        // Same initial target as Genres: at least 50 shows, retaining the last full page.
        val pages = if (more) 1 else 3
        repeat(pages) {
            currentCoroutineContext().ensureActive()
            if (row != null && !row!!.hasMore) return@repeat
            val page = row?.nextPage ?: 1
            require(BuildConfig.TMDB_API_KEY.isNotBlank()) { "TMDB API key is not configured." }
            val response = request("https://api.themoviedb.org/3/discover/tv?api_key=${BuildConfig.TMDB_API_KEY}" +
                "&language=en-US&include_adult=false&sort_by=popularity.desc&with_networks=${network.id}&page=$page") {
                check(it.isSuccessful) { "Could not load this network (HTTP ${it.code})." }
                JSONObject(it.body?.string() ?: error("TMDB returned an empty network page."))
            }
            val items = response.optJSONArray("results") ?: error("TMDB returned an invalid network page.")
            val added = (0 until items.length()).mapNotNull { index ->
                val obj = items.optJSONObject(index) ?: return@mapNotNull null
                val id = obj.optInt("id")
                val name = obj.optString("name").trim()
                if (id <= 0 || name.isBlank() || obj.optBoolean("adult", false)) return@mapNotNull null
                TitleItem(id, name, obj.optString("overview"),
                    obj.optString("poster_path").takeIf { it.startsWith("/") },
                    obj.optString("backdrop_path").takeIf { it.startsWith("/") },
                    "tv", obj.optDouble("vote_average", 0.0))
            }
            val returned = response.optInt("page", page)
            check(returned == page) { "TMDB returned an unexpected network page." }
            val total = response.optInt("total_pages", page).coerceIn(0, 500)
            row = TmdbNetworkRow((row?.titles.orEmpty() + added).distinctBy { it.id },
                page + 1, if (items.length() == 0) page else total)
            synchronized(rows) { rows[network.id] = row!! }
            if (row!!.titles.size >= 50 && !more) return@withPermit row!!
        }
        row ?: error("Could not load this network.")
    }
}
