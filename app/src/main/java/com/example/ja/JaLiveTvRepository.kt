package com.example.ja

import android.content.Context
import android.text.Html
import android.util.Log
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import java.util.Locale
import java.util.zip.GZIPInputStream

@androidx.compose.runtime.Immutable
internal data class JaLiveChannel(val id:String,val name:String,val logo:String,val urls:List<String>,
    val headers:Map<String,Map<String,String>> = emptyMap(),val categories:Set<String> = emptySet())
@androidx.compose.runtime.Immutable
internal data class JaLiveProgram(val channel:String,val start:Long,val end:Long,val title:String)
@androidx.compose.runtime.Immutable
internal data class JaLiveChannels(val channels:List<JaLiveChannel>,val saved:Boolean,val verifiedAt:String,val categoriesAvailable:Boolean=false)
internal data class JaLiveGuide(val programs:List<JaLiveProgram>,val fetched:Long,val saved:Boolean) {
    // Constructed on Dispatchers.IO; re-entering the screen does not regroup the entire EPG on Main.
    val byChannel:Map<String,List<JaLiveProgram>> = programs.groupBy{it.channel}
}

internal class JaLiveTvRepository(private val context:Context) {
    private companion object { val catalogLock=Mutex() }
    private val guideLock=Mutex()
    @Volatile private var memoryCatalog:Pair<JaLiveChannels,Long>?=null
    @Volatile private var memoryGuide:Pair<String,JaLiveGuide>?=null
    private val root=File(context.cacheDir,"live-tv").apply{mkdirs()}
    private val client=OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS)
        .callTimeout(120,TimeUnit.SECONDS).build()
    private fun request(url:String)=Request.Builder().url(url).header("User-Agent","JA/2.4.6 (https://github.com/jdaraver2011/ja_app)").build()
    private fun text(url:String,limit:Long,timeoutSeconds:Long=120):String = client.newCall(request(url)).also{it.timeout().timeout(timeoutSeconds,TimeUnit.SECONDS)}.execute().use { r ->
        if(!r.isSuccessful)throw IOException("HTTP ${r.code}")
        val body=r.body?:throw IOException("Empty response")
        LimitedInput(body.byteStream(),limit).bufferedReader().use{it.readText()}
    }
    suspend fun channels(force:Boolean=false,onCached:suspend(JaLiveChannels)->Unit={}):JaLiveChannels = withContext(Dispatchers.IO) { catalogLock.withLock {
        val now=System.currentTimeMillis()
        val zone=ZoneId.systemDefault()
        val localDate=Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val todaySix=localDate.atTime(6,0).atZone(zone).toInstant().toEpochMilli()
        val file=File(root,"direct-catalog.json")
        val legacy=File(root,"verified-us.json")
        val cachedRecord=sequenceOf(file,legacy).mapNotNull{candidate->
            val modified=candidate.lastModified()
            if(modified<=0L)null else runCatching {
                candidate to (memoryCatalog?.takeIf{candidate==file&&it.second==modified}?.first
                    ?:parseCatalog(candidate.readText(),false))
            }.getOrNull()
        }.firstOrNull()
        val cachedFile=cachedRecord?.first
        val cached=cachedRecord?.second
        val cachedAt=cachedFile?.lastModified()?:0L
        // The prior cache came from a remotely checked catalog; fetch the new direct sources once after upgrade.
        val due=cached==null||cachedFile!=file||refreshDueAfterSix(cachedAt,now)
        val prefs=context.getSharedPreferences("ja_live_tv_refresh",Context.MODE_PRIVATE)
        val attemptedToday=prefs.getString("automatic_attempt_date","")==localDate.toString()
        val shouldRefresh=force||(due&&!attemptedToday)
        if(cached!=null&&!shouldRefresh)return@withContext cached.also{memoryCatalog=it to cachedAt}
        if(cached==null&&!shouldRefresh)throw IOException("No catalog cache is available; automatic refresh was already attempted today")
        if(cached!=null)withContext(Dispatchers.Main){onCached(cached)}
        // Record the daily attempt before starting the request so failed network calls do not repeat on every app open.
        // A first install can fetch before 6:00 AM and still counts as today's automatic attempt.
        // A manual refresh after 6:00 AM also counts as today's attempt.
        if(!force || now>=todaySix)prefs.edit().putString("automatic_attempt_date",localDate.toString()).apply()
        try {
            val raw=downloadDirectCatalog()
            val parsed=parseCatalog(raw,false)
            write(file,raw);memoryCatalog=parsed to file.lastModified();parsed
        }catch(e:CancellationException){throw e}catch(e:Exception){
            cached?.copy(saved=true)?.also{memoryCatalog=it to cachedAt}?:throw e
        }
    }}
    private fun refreshDueAfterSix(lastDownload:Long,now:Long):Boolean {
        val zone=ZoneId.systemDefault()
        val todaySix=Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atTime(6,0).atZone(zone).toInstant().toEpochMilli()
        return lastDownload<=0L||(now>=todaySix&&lastDownload<todaySix)
    }
    private data class M3uEntry(val id:String,val name:String,val logo:String,val groups:String,val url:String)
    private data class M3uSource(val id:String,val category:String,val url:String)
    private suspend fun downloadDirectCatalog():String = supervisorScope {
        val sources=listOf(
            M3uSource("us","country-us","https://iptv-org.github.io/iptv/countries/us.m3u"),
            M3uSource("uk","country-uk","https://iptv-org.github.io/iptv/countries/uk.m3u"),
        )
        val results=sources.map{source->async {
            try{source to downloadSource(source)}
            catch(e:CancellationException){throw e}
            catch(e:Exception){Log.w("JA_LIVE_TV","CATALOG_SOURCE_FAILED source=${source.id} error=${e.message}",e);null}
        }}.awaitAll()
        val loaded=results.filterNotNull().map{it.second}
        val channels=mergeM3uChannels(loaded)
        if(channels.isEmpty())throw IOException("No live TV sources could be loaded")
        val failed=sources.filterIndexed{index,_->results[index]==null}.joinToString(","){it.id}
        if(failed.isNotBlank())Log.w("JA_LIVE_TV","CATALOG_PARTIAL sources=${loaded.size} failed=$failed channels=${channels.size}")
        catalogJson(channels)
    }
    private fun downloadSource(source:M3uSource):List<JaLiveChannel> {
        return parseM3u(text(source.url,30_000_000,120),source)
    }
    private fun decodeHtml(value:String):String=Html.fromHtml(value,Html.FROM_HTML_MODE_LEGACY).toString().trim()
    private fun htmlAttribute(tag:String,name:String):String {
        val pattern=Regex("""(?i)(?:^|\s)${Regex.escape(name)}\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")
        val match=pattern.find(tag)?:return ""
        return decodeHtml(match.groups[1]?.value?:match.groups[2]?.value?:match.groups[3]?.value.orEmpty())
    }
    private fun parseM3u(raw:String,source:M3uSource):List<JaLiveChannel> {
        if(!raw.trimStart().startsWith("#EXTM3U"))throw IOException("${source.id.uppercase(Locale.ROOT)} playlist was not valid M3U")
        val rows=mutableListOf<M3uEntry>();var current:Triple<String,String,String>?=null
        for(rawLine in raw.lineSequence()){
            val line=rawLine.trim();if(line.isEmpty())continue
            if(line.startsWith("#EXTINF:",true)){
                val comma=line.lastIndexOf(',')
                val name=if(comma>=0)decodeHtml(line.substring(comma+1)) else htmlAttribute(line,"tvg-name")
                current=Triple(htmlAttribute(line,"tvg-id"),name,htmlAttribute(line,"tvg-logo")+"\u0000"+htmlAttribute(line,"group-title"))
            }else if(current!=null&&!line.startsWith("#")){
                if(webUrl(line)){
                    val bits=current!!;val metadata=bits.third.split('\u0000',limit=2)
                    rows.add(M3uEntry(bits.first,bits.second,metadata[0],metadata.getOrElse(1){""},line))
                }
                current=null
            }
        }
        if(rows.isEmpty())throw IOException("${source.id.uppercase(Locale.ROOT)} playlist contained no channels")
        val merged=linkedMapOf<String,JaLiveChannel>()
        for(row in rows){
            val name=row.name.ifBlank{row.id.ifBlank{"Unnamed channel"}}
            val tvgId=row.id.trim()
            val key=if(tvgId.isNotBlank())"id:${tvgId.lowercase(Locale.ROOT)}"
                else "${source.id}:name:${slug(name)}"
            val stableId=if(tvgId.isNotBlank())tvgId else "${source.id}-${slug(name)}"
            val groups=row.groups.split(Regex("[|;]" )).map{slug(decodeHtml(it))}.filter{it.isNotBlank()
                &&it !in setOf("us","usa","united-states","uk","gb","united-kingdom")}
            val categories=(groups+source.category).toSet()
            val old=merged[key]
            merged[key]=if(old==null)JaLiveChannel(stableId,name,row.logo,listOf(row.url),categories=categories)
                else old.copy(name=old.name.ifBlank{ name },logo=old.logo.ifBlank{row.logo},urls=(old.urls+row.url).distinct(),categories=old.categories+categories)
        }
        return merged.values.toList()
    }
    private fun slug(value:String):String=value.trim().lowercase(Locale.ROOT).replace("&"," and ")
        .replace(Regex("[^a-z0-9]+"),"-").trim('-')
    private fun mergeM3uChannels(feeds:List<List<JaLiveChannel>>):List<JaLiveChannel> = feeds.flatten()
        .groupBy{it.id}.values.map{same->
            same.drop(1).fold(same.first()){base,next->base.copy(urls=(base.urls+next.urls).distinct(),
                categories=base.categories+next.categories,logo=base.logo.ifBlank{next.logo})}
        }.sortedBy{it.name.lowercase(Locale.ROOT)}
    private fun catalogJson(channels:List<JaLiveChannel>):String {
        val entries=JSONArray();val counts=JSONObject()
        for(channel in channels){
            val streams=JSONArray();channel.urls.forEach{streams.put(JSONObject().put("url",it))}
            val groups=JSONArray();channel.categories.sorted().forEach{groups.put(it);counts.put(it,counts.optInt(it)+1)}
            entries.put(JSONObject().put("id",channel.id).put("name",channel.name).put("logo",channel.logo)
                .put("categories",groups).put("streams",streams))
        }
        return JSONObject().put("schema",1).put("generatedAt",Instant.now().toString()).put("channels",entries)
            .put("counts",JSONObject().put("categories",counts)).toString()
    }
    private fun parseCatalog(raw:String,saved:Boolean):JaLiveChannels {
        val doc=JSONObject(raw)
        require(doc.getInt("schema")==1)
        val verified=doc.getString("generatedAt")
        val stamp=java.time.Instant.parse(verified).toEpochMilli()
        require(stamp>0&&stamp<=System.currentTimeMillis()+300_000)
        val entries=doc.getJSONArray("channels")
        require(entries.length() in 1..10000)
        val channels=(0 until entries.length()).map{entries.getJSONObject(it)}.mapNotNull{c ->
            val streams=c.getJSONArray("streams")
            val headers=mutableMapOf<String,Map<String,String>>()
            val urls=(0 until streams.length()).map{streams.getJSONObject(it)}.mapNotNull{stream ->
                val url=stream.optString("url")
                if(!webUrl(url))null else {
                    val h=mutableMapOf<String,String>()
                    stream.optString("userAgent").takeIf{it.isNotBlank()&&!it.contains('\n')&&!it.contains('\r')}?.let{h["User-Agent"]=it}
                    stream.optString("referrer").takeIf{webUrl(it)}?.let{h["Referer"]=it}
                    headers[url]=h;url
                }
            }.distinct()
            val groups=c.optJSONArray("categories")
            val categories=if(groups==null)mutableSetOf<String>() else (0 until groups.length()).map{groups.optString(it)}.filter{it.isNotBlank()}.toMutableSet()
            // Older cached catalogs were US-only and did not carry an explicit source-country tag.
            if("country-us" !in categories&&"country-uk" !in categories&&"test" !in categories)categories.add("country-us")
            if(urls.isEmpty()||("test" in categories&&"country-us" !in categories&&"country-uk" !in categories))null
            else JaLiveChannel(c.getString("id"),c.getString("name"),c.optString("logo"),urls,headers,categories)
        }.distinctBy{it.id}.sortedBy{it.name.lowercase()}
        require(channels.isNotEmpty())
        return JaLiveChannels(channels,saved,verified,doc.optJSONObject("counts")?.has("categories")==true)
    }
    suspend fun playbackChannel(channel:JaLiveChannel,force:Boolean=false):JaLiveChannel =
        if(!force)memoryCatalog?.first?.channels?.firstOrNull{it.id==channel.id}
            ?:channels(false).channels.firstOrNull{it.id==channel.id}?:throw IOException("Channel no longer verified live")
        else channels(true).channels.firstOrNull{it.id==channel.id}?:throw IOException("Channel no longer verified live")

    private fun signature(channels:List<JaLiveChannel>):String = java.security.MessageDigest.getInstance("SHA-256")
        .digest((channels.map{it.id}.sorted().joinToString("\n")+"\npluto-v1").toByteArray()).joinToString(""){"%02x".format(it)}
    suspend fun cachedGuide(channels:List<JaLiveChannel>):JaLiveGuide? = withContext(Dispatchers.IO){
        val key=signature(channels)
        memoryGuide?.takeIf{it.first==key}?.let{return@withContext it.second}
        val loaded=runCatching {
            val obj=JSONObject(File(root,"guide-$key.json").readText())
            require(obj.getString("signature")==key)
            val now=System.currentTimeMillis();val a=obj.getJSONArray("programs")
            val entries=(0 until a.length()).mapNotNull{index->val item=a.getJSONArray(index)
                if(item.getLong(2)<=now)null else JaLiveProgram(item.getString(0),item.getLong(1),item.getLong(2),item.getString(3))}
            JaLiveGuide(entries,obj.getLong("fetched"),obj.optBoolean("saved",false))
        }.getOrNull()
        loaded?.also{memoryGuide=key to it}
    }
    private data class Feed(val programs:List<JaLiveProgram>,val fetched:Long,val stale:Boolean)
    // StreamingApp retains this work across channel playback. Concurrent providers publish useful partial schedules.
    suspend fun guide(channels:List<JaLiveChannel>,force:Boolean=false,onPartial:suspend(JaLiveGuide)->Unit={}):JaLiveGuide = withContext(Dispatchers.IO){guideLock.withLock{
        val began=android.os.SystemClock.elapsedRealtime()
        val now=System.currentTimeMillis();val key=signature(channels)
        val cached=cachedGuide(channels)
        if(!force&&cached!=null){
            val rebuiltAt=File(root,"guide-$key.json").lastModified()
            val coversNow=cached.programs.any{it.start<=now&&it.end>now}
            if((coversNow&&!refreshDueAfterSix(rebuiltAt,now))||
                (!cached.saved&&JaLiveTvRules.fresh(cached.fetched,now,21_600_000)))return@withContext cached
        }
        val cancellation=currentCoroutineContext()
        suspend fun loadFeed(url:String,name:String,limit:Long,aliases:Map<String,String>):Feed? {
            val file=File(root,name);var stale=false
            if(force||!JaLiveTvRules.fresh(file.lastModified(),now,21_600_000)) {
                try {
                    client.newCall(request(url)).execute().use{response->
                        if(!response.isSuccessful)throw IOException("Guide HTTP ${response.code}")
                        val body=response.body?:throw IOException("Empty guide")
                        val temp=File(root,"$name.download")
                        try{
                            LimitedInput(body.byteStream(),limit){cancellation.ensureActive()}.use{input->temp.outputStream().use{input.copyTo(it)}}
                            GZIPInputStream(temp.inputStream()).use{it.read()}
                            if(!temp.renameTo(file))throw IOException("Guide cache write failed")
                        }finally{temp.delete()}
                    }
                }catch(e:CancellationException){throw e}catch(_:Exception){stale=true;if(!file.exists())return null}
            }
            return try{Feed(parseGuide(file,channels,now,aliases){cancellation.ensureActive()},file.lastModified(),stale)}
            catch(e:CancellationException){throw e}catch(_:Exception){null}
        }
        val exact=channels.flatMap{channel->channel.urls.map{JaLiveTvRules.plutoId(it)}.filter{it.isNotBlank()}.distinct().map{it to channel.id}}
            .groupBy({it.first},{it.second}).mapNotNull{(id,targets)->targets.distinct().singleOrNull()?.let{id to it}}.toMap()
        val parts=mutableMapOf<String,Feed>();val partialLock=Mutex()
        suspend fun publish(provider:String,feed:Feed?) {
            if(feed==null)return
            partialLock.withLock {
                parts[provider]=feed
                val pluto=parts["pluto"]?.programs.orEmpty();val plutoIds=pluto.map{it.channel}.toSet()
                val primary=parts["primary"]?.programs.orEmpty().filterNot{it.channel in plutoIds}
                val updated=primary+pluto;val updatedIds=updated.map{it.channel}.toSet()
                val partial=JaLiveGuide(updated+cached?.programs.orEmpty().filterNot{it.channel in updatedIds},now,true)
                withContext(Dispatchers.Main){onPartial(partial)}
            }
        }
        coroutineScope {
            val primaryJob=async { loadFeed("https://epg.pw/xmltv/epg_US.xml.gz","us-epg.xml.gz",48_000_000,emptyMap()).also{publish("primary",it)} }
            val plutoJob=async { if(exact.isEmpty())null else loadFeed("https://i.mjh.nz/PlutoTV/us.xml.gz","pluto-us.xml.gz",12_000_000,exact).also{publish("pluto",it)} }
            primaryJob.await();plutoJob.await()
        }
        if(parts.isEmpty())return@withContext cached?.copy(saved=true)?:throw IOException("Guide unavailable")
        val pluto=parts["pluto"]?.programs.orEmpty();val providerChannels=pluto.map{it.channel}.toSet()
        val programs=parts["primary"]?.programs.orEmpty().filterNot{it.channel in providerChannels}+pluto
        val degraded=parts["primary"]==null||(exact.isNotEmpty()&&parts["pluto"]==null)||parts.values.any{it.stale}
        val fetched=parts.values.minOf{it.fetched}
        val result=JaLiveGuide(programs,fetched,degraded)
        val a=JSONArray();programs.forEach{a.put(JSONArray().put(it.channel).put(it.start).put(it.end).put(it.title))}
        write(File(root,"guide-$key.json"),JSONObject().put("signature",key).put("fetched",fetched).put("saved",degraded).put("programs",a).toString())
        memoryGuide=key to result
        android.util.Log.i("JA_LIVE_TV","GUIDE_READY programs=${programs.size} channels=${result.byChannel.size} saved=$degraded elapsedMs=${android.os.SystemClock.elapsedRealtime()-began}")
        result
    }}
    private fun parseGuide(file:File,channels:List<JaLiveChannel>,now:Long,providerAliases:Map<String,String>,checkActive:()->Unit):List<JaLiveProgram> {
        val ids=channels.associateBy{it.id}
        val bases=channels.groupBy{JaLiveTvRules.baseId(it.id)}
        val names=if(providerAliases.isNotEmpty())emptyMap() else channels.flatMap{channel -> JaLiveTvRules.guideKeys(channel.id,channel.name).map{it to channel}}.groupBy({it.first},{it.second})
        val aliases=providerAliases.toMutableMap();val guideNames=mutableMapOf<String,MutableSet<String>>()
        val candidates=mutableMapOf<String,MutableSet<String>>()
        val keysByGuide=mutableMapOf<String,MutableSet<String>>()
        val programs=mutableListOf<JaLiveProgram>()
        GZIPInputStream(file.inputStream()).use{gzip -> LimitedInput(gzip,256_000_000).use{input ->
            val parser=Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,false)
            parser.setInput(input,"UTF-8")
            var event=parser.eventType;var guideId="";var channelMode=false;var schedule=false
            var start=0L;var end=0L;var programId="";var title=""
            while(event!=XmlPullParser.END_DOCUMENT) {
                checkActive()
                if(event==XmlPullParser.DOCDECL)throw IOException("Guide DTD unsupported")
                if(event==XmlPullParser.START_TAG)when(parser.name) {
                    "channel"->{guideId=parser.getAttributeValue(null,"id")?:"";channelMode=true
                        val exact=ids[guideId]?.id?:bases[JaLiveTvRules.baseId(guideId)]?.singleOrNull()?.id
                        if(exact!=null)aliases[guideId]=exact
                    }
                    "display-name"->if(channelMode){
                        val key=JaLiveTvRules.guideName(parser.nextText())
                        guideNames.getOrPut(key){mutableSetOf()}.add(guideId)
                        keysByGuide.getOrPut(guideId){mutableSetOf()}.add(key)
                        names[key]?.singleOrNull()?.let{candidates.getOrPut(guideId){mutableSetOf()}.add(it.id)}
                    }
                    "programme"->{
                        if(!schedule){
                            candidates.forEach{(id,targets)->if(id !in aliases&&targets.size==1){
                                val keys=keysByGuide[id].orEmpty()
                                if(keys.any{guideNames[it]?.size==1})aliases[id]=targets.first()
                            }};schedule=true
                        }
                        programId=aliases[parser.getAttributeValue(null,"channel")]?:""
                        if(programId.isNotEmpty()){
                            start=JaLiveTvRules.xmlTime(parser.getAttributeValue(null,"start")?:"")
                            end=JaLiveTvRules.xmlTime(parser.getAttributeValue(null,"stop")?:"")
                        }
                        title=""
                    }
                    "title"->if(programId.isNotEmpty()&&title.isEmpty())title=parser.nextText().take(300)
                }
                if(event==XmlPullParser.END_TAG)when(parser.name){
                    "channel"->channelMode=false
                    "programme"->{if(programId.isNotEmpty()&&start>=0&&end>start&&end>now&&start<now+172_800_000&&title.isNotBlank()&&programs.size<100_000)
                        programs.add(JaLiveProgram(programId,start,end,title));programId=""}
                }
                event=parser.next()
            }
        }}
        return programs.distinct().sortedBy{it.start}
    }
    private fun write(file:File,text:String){val temp=File(file.path+".tmp");temp.writeText(text);if(!temp.renameTo(file))throw IOException("Cache write failed")}
    private fun webUrl(value:String)=runCatching{val u=java.net.URI(value);u.scheme in setOf("http","https")&&!u.host.isNullOrBlank()&&u.userInfo==null}.getOrDefault(false)
    private class LimitedInput(input:InputStream,private val limit:Long,private val checkActive:()->Unit={}):FilterInputStream(input){
        private var count=0L
        private fun add(n:Int){checkActive();if(n>0){count+=n;if(count>limit)throw IOException("Response exceeds limit")}}
        override fun read():Int=super.read().also{if(it>=0)add(1)}
        override fun read(b:ByteArray,off:Int,len:Int):Int=`in`.read(b,off,len).also{add(it)}
    }
}
