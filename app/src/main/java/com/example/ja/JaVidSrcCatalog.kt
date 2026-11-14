package com.example.ja

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Provider-declared catalogue/quality only. Never touches Gecko sessions or waits in Play. */
object JaVidSrcCatalog {
    data class Snapshot(
        val ready: Boolean=false, val generation: Long=0L, val syncedAt: Long=0L,
        val movies: Set<Int> = emptySet(), val shows: Set<Int> = emptySet(), val episodes: Long=0L,
        val busy: Boolean=false, val error: String?=null
    )
    data class QualityState(val cams: Map<Int,TitleItem> = emptyMap(), val missing: Set<Int> = emptySet())
    private val _snapshot=MutableStateFlow(Snapshot())
    val snapshot=_snapshot.asStateFlow()
    private val _quality=MutableStateFlow(QualityState())
    val quality=_quality.asStateFlow()
    private val _promotions=MutableStateFlow(0L)
    val promotions=_promotions.asStateFlow()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val initialized=CompletableDeferred<Unit>()
    private val syncMutex=Mutex()
    private val qualityLock=Any()
    private val records=ConcurrentHashMap<Int,JaVidSrcDatabase.Quality>()
    private val pending=ConcurrentHashMap.newKeySet<Int>()
    private val queue=Channel<TitleItem>(Channel.UNLIMITED)
    @Volatile private var foreground=true
    @Volatile private var playing=false
    @Volatile private var promotePending=false
    @Volatile private var started=false
    private lateinit var db: JaVidSrcDatabase
    private lateinit var app: Context
    private val feedsClient=OkHttpClient.Builder().connectTimeout(12,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS)
        .callTimeout(60,TimeUnit.SECONDS).build()
    private val infoClient=OkHttpClient.Builder().connectTimeout(5,TimeUnit.SECONDS).readTimeout(6,TimeUnit.SECONDS)
        .callTimeout(8,TimeUnit.SECONDS).build()
    private const val UA="JA-Catalog-Review/1.0"

    @Synchronized fun initialize(context: Context) {
        if(started)return
        started=true;app=context.applicationContext
        scope.launch {
            try {
                db=JaVidSrcDatabase(app)
                records.putAll(db.qualities().associateBy { it.id })
                publishQuality()
                _snapshot.value=db.snapshot()
            }catch(e:Exception) { _snapshot.value=_snapshot.value.copy(error="Could not read VidSrc cache")
                Log.w("JA_CATALOG_SYNC","CACHE_READ_FAILED",e)
            }finally { initialized.complete(Unit) }
        }
        repeat(2) { scope.launch {
            initialized.await()
            for(item in queue) {
                try {
                    while(!foreground || playing)delay(500)
                    refreshQuality(item)
                }catch(cancel:CancellationException){throw cancel}
                catch(e:Exception){Log.w("JA_CATALOG_SYNC","QUALITY_CHECK_FAILED id=${item.id}",e)}
                finally {pending.remove(item.id)}
            }
        } }
        scope.launch {
            while(isActive) {
                delay(1000)
                if(promotePending && pending.isEmpty() && foreground && !playing) {
                    promotePending=false
                    _promotions.value=_promotions.value+1L
                }
            }
        }
    }
    fun foreground(value: Boolean) { foreground=value }
    fun playbackBusy(value: Boolean) { playing=value }
    fun includeMovie(id: Int): Boolean {
        val s=_snapshot.value;val q=_quality.value
        return s.ready && id in s.movies && id !in q.cams && id !in q.missing
    }
    fun observe(item: TitleItem) {
        if(!started || item.type!="movie" || item.id<=0)return
        val now=System.currentTimeMillis();val old=records[item.id]
        if(old!=null && !JaVidSrcRules.due(old.checked,old.retryAfter,now))return
        if(!pending.add(item.id))return
        if(queue.trySend(item).isFailure)pending.remove(item.id)
    }
    fun refreshOverdue(force: Boolean=false) {
        if(!started)return
        scope.launch {
            initialized.await()
            syncMutex.withLock {
                val now=System.currentTimeMillis()
                val prefs=app.getSharedPreferences("ja_vidsrc_sync",Context.MODE_PRIVATE)
                val retry=prefs.getLong("retry_after",0L)
                if(force || JaVidSrcRules.due(_snapshot.value.syncedAt,retry,now)) {
                    val directory=File(app.cacheDir,"vidsrc-${System.nanoTime()}")
                    directory.mkdirs()
                    _snapshot.value=_snapshot.value.copy(busy=true,error=null)
                    try {
                        val files=coroutineScope {
                            mapOf(1 to "movie_tmdb.txt",2 to "tv_tmdb.txt",3 to "eps_tmdb.txt").map { (kind,name) -> async {
                                val file=File(directory,name)
                                downloadFeed(name,file)
                                kind to file
                            } }.awaitAll().toMap()
                        }
                        val fresh=db.install(files,now)
                        _snapshot.value=fresh
                        prefs.edit().remove("retry_after").apply()
                        Log.i("JA_CATALOG_SYNC","SYNC_OK movies=${fresh.movies.size} shows=${fresh.shows.size} episodes=${fresh.episodes}")
                    }catch(cancel:CancellationException){throw cancel}
                    catch(e:Exception) {
                        prefs.edit().putLong("retry_after",System.currentTimeMillis()+JaVidSrcRules.RETRY_MS).apply()
                        _snapshot.value=_snapshot.value.copy(busy=false,error=if(_snapshot.value.ready)
                            "VidSrc refresh failed; using cached catalogue" else "Could not download the VidSrc catalogue. Please retry.")
                        Log.w("JA_CATALOG_SYNC","SYNC_FAILED_PREVIOUS_SNAPSHOT_RETAINED",e)
                    }finally { directory.deleteRecursively();_snapshot.value=_snapshot.value.copy(busy=false) }
                }
                // Quality changes need checking even when the ID list is unchanged.
                records.values.filter { it.label=="CAM" || it.label=="NOT_LISTED" }.forEach { observe(it.title) }
            }
        }
    }
    private fun downloadFeed(name: String, output: File) {
        val request=Request.Builder().url("https://vidsrc.sh/ids/$name").header("User-Agent",UA).header("Accept","text/plain").build()
        feedsClient.newCall(request).execute().use { response ->
            if(!response.isSuccessful)error("ID feed HTTP ${response.code}")
            val body=response.body ?: error("Empty ID response")
            val contentType=response.header("Content-Type").orEmpty().lowercase()
            if(!contentType.startsWith("text/plain"))error("Invalid ID feed content type")
            if(body.contentLength()>JaVidSrcRules.MAX_BYTES)error("ID feed too large")
            body.byteStream().use { input -> output.outputStream().use { out ->
                val buffer=ByteArray(32768);var total=0L
                while(true) {
                    val n=input.read(buffer);if(n<0)break
                    total+=n;if(total>JaVidSrcRules.MAX_BYTES)error("ID feed too large")
                    out.write(buffer,0,n)
                }
            } }
        }
    }
    private fun refreshQuality(item: TitleItem) {
        val now=System.currentTimeMillis();val old=records[item.id]
        if(old!=null && !JaVidSrcRules.due(old.checked,old.retryAfter,now))return
        try {
            val request=Request.Builder().url("https://vidsrc.sh/info/movie/${item.id}.json")
                .header("User-Agent",UA).header("Accept","application/json").build()
            val label=infoClient.newCall(request).execute().use { response ->
                val contentType=response.header("Content-Type").orEmpty().lowercase()
                if(!contentType.contains("application/json"))error("Non-JSON content info response")
                val body=response.body ?: error("Empty content info")
                if(body.contentLength()>65536L)error("Oversize content info")
                val bytes=body.byteStream().use { input ->
                    val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096)
                    while(true) { val n=input.read(buffer);if(n<0)break
                        if(out.size()+n>65536)error("Oversize content info")
                        out.write(buffer,0,n)
                    }
                    out.toByteArray()
                }
                if(bytes.size>65536)error("Oversize content info")
                val json=JSONObject(String(bytes,Charsets.UTF_8))
                if(response.code==404 && json.optInt("status_code")==404) "NOT_LISTED"
                else {
                    if(response.code!=200 || json.optInt("status_code")!=200 || json.optString("type")!="movie" ||
                        json.optString("tmdb_id").toIntOrNull()!=item.id)error("Unverified content info response")
                    JaVidSrcRules.quality(json.optString("quality")).ifEmpty { error("Unknown quality label") }
                }
            }
            val record=JaVidSrcDatabase.Quality(item.id,label,now,0L,item)
            db.save(record);records[item.id]=record
            publishQuality()
            if((old?.label=="CAM" || old?.label=="NOT_LISTED") && label!="CAM" && label!="NOT_LISTED")promotePending=true
            Log.i("JA_CATALOG_SYNC","QUALITY_CONFIRMED id=${item.id} quality=$label")
        }catch(e:Exception) {
            // A 403, timeout, malformed JSON, or unknown label never becomes CAM or not-found.
            val record=old?.copy(retryAfter=now+JaVidSrcRules.RETRY_MS)
                ?: JaVidSrcDatabase.Quality(item.id,"",0L,now+JaVidSrcRules.RETRY_MS,item)
            records[item.id]=record
            try {db.save(record)}catch(_:Exception){}
            Log.i("JA_CATALOG_SYNC","QUALITY_RETRY_LATER id=${item.id}")
        }
    }
    private fun publishQuality() = synchronized(qualityLock) {
        val cams=records.values.filter { it.label=="CAM" }.associate { it.id to it.title }
        val missing=records.values.filter { it.label=="NOT_LISTED" }.map { it.id }.toSet()
        val next=QualityState(cams,missing)
        if(next!=_quality.value)_quality.value=next
    }
}
