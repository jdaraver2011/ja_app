package com.example.ja

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/** One complete U.S. theatrical snapshot. CAM is a routing label, not verified quality. */
internal object JaNowPlayingCatalog {
    data class Snapshot(
        val ready: Boolean = false, val generation: Long = 0L,
        val date: LocalDate? = null, val ids: Set<Int> = emptySet(),
        val titles: List<TitleItem> = emptyList(), val busy: Boolean = false,
        val error: String? = null
    )
    private val mutable = MutableStateFlow(Snapshot())
    val snapshot = mutable.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initialized = CompletableDeferred<Unit>()
    private val mutex = Mutex()
    private lateinit var file: AtomicFile
    @Volatile private var started = false
    private var retryAfter = 0L

    @Synchronized fun initialize(context: Context) {
        if (started) return
        started = true
        file = AtomicFile(File(context.applicationContext.filesDir, "tmdb-now-playing-us-v1.json"))
        scope.launch {
            try {
                if (file.baseFile.exists()) {
                    val bytes = file.openRead().use { it.readBytes() }
                    require(bytes.size <= 10_000_000)
                    val saved = JSONObject(String(bytes, Charsets.UTF_8))
                    require(saved.getString("region") == "US")
                    val rows = saved.getJSONArray("results")
                    mutable.value = prepareSnapshot(rows, LocalDate.parse(saved.getString("date")))
                }
            } catch (_: Exception) {
                Log.w("JA_NOW_PLAYING", "CACHE_UNAVAILABLE")
            } finally { initialized.complete(Unit) }
        }
    }

    fun includeOutside(type: String, id: Int): Boolean {
        val current = mutable.value
        return JaNowPlayingBatch.includeOutside(type, id, current.ready, current.ids)
    }

    fun refreshOverdue(force: Boolean = false) {
        if (!started) return
        scope.launch {
            initialized.await()
            mutex.withLock {
                val today = LocalDate.now()
                if (!force && (mutable.value.date == today || System.currentTimeMillis() < retryAfter))
                    return@withLock
                mutable.value = mutable.value.copy(busy = true, error = null)
                try {
                    val batch = JaNowPlayingBatch()
                    val rows = JSONArray()
                    var window: String? = null
                    while (batch.hasNext()) {
                        val page = batch.nextPage()
                        val json = fetch(page)
                        val dates = json.getJSONObject("dates")
                        val signature = dates.getString("minimum") + ":" + dates.getString("maximum")
                        if (window != null) require(signature == window) { "Theatrical date window changed" }
                        window = signature
                        val entries = json.getJSONArray("results")
                        val ids = IntArray(entries.length()) { entries.getJSONObject(it).getInt("id") }
                        batch.accept(json.getInt("page"), json.getInt("total_pages"), json.getInt("total_results"), ids)
                        for (i in 0 until entries.length()) rows.put(entries.getJSONObject(i))
                    }
                    check(batch.complete()) { "Incomplete Now Playing list" }
                    // Publish only after every page and the durable cache have succeeded.
                    val fresh = prepareSnapshot(rows, today)
                    val cache = JSONObject().put("region", "US").put("date", today.toString()).put("results", rows)
                    val bytes = cache.toString().toByteArray(Charsets.UTF_8)
                    require(bytes.size <= 10_000_000)
                    val stream = file.startWrite()
                    try { stream.write(bytes); file.finishWrite(stream) }
                    catch (problem: Exception) { file.failWrite(stream); throw problem }
                    mutable.value = fresh
                    retryAfter = 0L
                    Log.i("JA_NOW_PLAYING", "SYNC_OK region=US movies=${mutable.value.ids.size}")
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) {
                    retryAfter = System.currentTimeMillis() + 15 * 60_000L
                    mutable.value = mutable.value.copy(error = if (mutable.value.ready)
                        "Now Playing refresh failed; using the saved list."
                        else "Could not load Now Playing. Movie browsing will resume after Retry.")
                    Log.w("JA_NOW_PLAYING", "SYNC_FAILED_PREVIOUS_SNAPSHOT_RETAINED")
                } finally { mutable.value = mutable.value.copy(busy = false) }
            }
        }
    }

    private fun fetch(page: Int): JSONObject {
        require(BuildConfig.TMDB_API_KEY.isNotBlank()) { "TMDB key missing" }
        val url = HttpUrl.Builder().scheme("https").host("api.themoviedb.org")
            .addPathSegments("3/movie/now_playing")
            .addQueryParameter("api_key", BuildConfig.TMDB_API_KEY)
            .addQueryParameter("region", "US").addQueryParameter("language", "en-US")
            .addQueryParameter("page", page.toString()).build()
        return JaTmdbHttp.client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful) { "Now Playing HTTP ${response.code}" }
            val body = response.body ?: error("Empty Now Playing response")
            check(body.contentLength() <= 1_000_000)
            val bytes = body.byteStream().use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(out.size() + count <= 1_000_000)
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
            JSONObject(String(bytes, Charsets.UTF_8))
        }
    }

    private fun prepareSnapshot(rows: JSONArray, date: LocalDate): Snapshot {
        val ids = linkedSetOf<Int>()
        val titles = mutableListOf<TitleItem>()
        for (i in 0 until rows.length()) {
            val obj = rows.getJSONObject(i)
            val id = obj.getInt("id")
            val title = obj.getString("title")
            require(id > 0 && title.isNotBlank() && ids.add(id))
            if (!obj.optBoolean("adult", false)) titles.add(TitleItem(
                id, title, obj.optString("overview"),
                obj.optString("poster_path").takeIf { it.startsWith("/") },
                obj.optString("backdrop_path").takeIf { it.startsWith("/") },
                "movie", obj.optDouble("vote_average", 0.0)))
        }
        return Snapshot(true, mutable.value.generation + 1L, date, ids, titles)
    }
}
