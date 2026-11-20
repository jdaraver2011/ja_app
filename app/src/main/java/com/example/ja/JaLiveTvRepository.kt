package com.example.ja

import android.content.Context
import android.util.Xml
import kotlinx.coroutines.Dispatchers
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
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

internal data class JaLiveChannel(val id:String,val name:String,val logo:String,val urls:List<String>,
    val headers:Map<String,Map<String,String>> = emptyMap())
internal data class JaLiveProgram(val channel:String,val start:Long,val end:Long,val title:String)
internal data class JaLiveChannels(val channels:List<JaLiveChannel>,val saved:Boolean,val verifiedAt:String)
internal data class JaLiveGuide(val programs:List<JaLiveProgram>,val fetched:Long,val saved:Boolean)

internal class JaLiveTvRepository(private val context:Context) {
    private companion object { val catalogLock=Mutex() }
    private val root=File(context.cacheDir,"live-tv").apply{mkdirs()}
    private val client=OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS)
        .callTimeout(120,TimeUnit.SECONDS).build()
    private fun request(url:String)=Request.Builder().url(url).header("User-Agent","JA/2.4.3 (https://github.com/jdaraver2011/ja_app)").build()
    private fun text(url:String,limit:Long,timeoutSeconds:Long=120):String = client.newCall(request(url)).also{it.timeout().timeout(timeoutSeconds,TimeUnit.SECONDS)}.execute().use { r ->
        if(!r.isSuccessful)throw IOException("HTTP ${r.code}")
        val body=r.body?:throw IOException("Empty response")
        LimitedInput(body.byteStream(),limit).bufferedReader().use{it.readText()}
    }
    suspend fun channels(force:Boolean=false):JaLiveChannels = withContext(Dispatchers.IO) { catalogLock.withLock {
        val file=File(root,"verified-us.json")
        val cached=runCatching{parseCatalog(file.readText(),false)}.getOrNull()
        if(!force&&cached!=null&&JaLiveTvRules.fresh(file.lastModified(),System.currentTimeMillis(),86_400_000))
            return@withContext cached
        try {
            val raw=text("https://raw.githubusercontent.com/jdaraver2011/ja_app/live-tv-data/catalog.json",8_000_000,25)
            val parsed=parseCatalog(raw,false)
            write(file,raw);parsed
        }catch(e:CancellationException){throw e}catch(e:Exception){
            cached?.copy(saved=true)?:throw e
        }
    }}
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
            if(urls.isEmpty())null else JaLiveChannel(c.getString("id"),c.getString("name"),c.optString("logo"),urls,headers)
        }.distinctBy{it.id}.sortedBy{it.name.lowercase()}
        require(channels.isNotEmpty())
        return JaLiveChannels(channels,saved,verified)
    }
    suspend fun playbackChannel(channel:JaLiveChannel,force:Boolean=false):JaLiveChannel =
        channels(force).channels.firstOrNull{it.id==channel.id}?:throw IOException("Channel no longer verified live")

    // One compressed download per six hours; keep only matching channel schedules for 48 hours.
    suspend fun guide(channels:List<JaLiveChannel>,force:Boolean=false):JaLiveGuide = withContext(Dispatchers.IO) {
        val cancellation=currentCoroutineContext()
        val now=System.currentTimeMillis()
        val wanted=channels.map{it.id}.toSet()
        val signature=java.security.MessageDigest.getInstance("SHA-256").digest((wanted.sorted().joinToString("\n")+"\npluto-v1").toByteArray()).joinToString(""){"%02x".format(it)}
        val file=File(root,"guide-$signature.json")
        val cached=runCatching{JSONObject(file.readText())}.getOrNull()
        fun saved(obj:JSONObject,stale:Boolean):JaLiveGuide {
            val a=obj.getJSONArray("programs")
            return JaLiveGuide((0 until a.length()).map{val p=a.getJSONArray(it);JaLiveProgram(p.getString(0),p.getLong(1),p.getLong(2),p.getString(3))},obj.getLong("fetched"),stale)
        }
        if(!force&&cached?.optString("signature")==signature&&JaLiveTvRules.fresh(cached.optLong("fetched"),now,21_600_000))return@withContext saved(cached,false)
        try {
            val compressed=File(root,"us-epg.xml.gz")
            if(force||!JaLiveTvRules.fresh(compressed.lastModified(),now,21_600_000)) {
                client.newCall(request("https://epg.pw/xmltv/epg_US.xml.gz")).execute().use{r ->
                    if(!r.isSuccessful)throw IOException("Guide HTTP ${r.code}")
                    val body=r.body?:throw IOException("Empty guide")
                    val temp=File(root,"guide-download.tmp")
                    try {LimitedInput(body.byteStream(),48_000_000){cancellation.ensureActive()}.use{input -> temp.outputStream().use{input.copyTo(it)}}
                        GZIPInputStream(temp.inputStream()).use{it.read()}
                        if(!temp.renameTo(compressed))throw IOException("Guide cache write failed")
                    }finally{temp.delete()}
                }
            }
            val primary=parseGuide(compressed,channels,now,emptyMap()){cancellation.ensureActive()}
            val pluto=try {
                val exact=channels.flatMap{channel -> channel.urls.map{JaLiveTvRules.plutoId(it)}.filter{it.isNotBlank()}.distinct().map{it to channel.id}}
                    .groupBy({it.first},{it.second}).mapNotNull{(id,targets)->targets.distinct().singleOrNull()?.let{id to it}}.toMap()
                if(exact.isEmpty())emptyList() else {
                    val plutoFile=File(root,"pluto-us.xml.gz")
                    if(force||!JaLiveTvRules.fresh(plutoFile.lastModified(),now,21_600_000)) {
                        client.newCall(request("https://i.mjh.nz/PlutoTV/us.xml.gz")).execute().use{r ->
                            if(!r.isSuccessful)throw IOException("Pluto guide HTTP ${r.code}")
                            val body=r.body?:throw IOException("Empty Pluto guide")
                            val temp=File(root,"pluto-guide-download.tmp")
                            try{LimitedInput(body.byteStream(),12_000_000){cancellation.ensureActive()}.use{input->temp.outputStream().use{input.copyTo(it)}}
                                GZIPInputStream(temp.inputStream()).use{it.read()}
                                if(!temp.renameTo(plutoFile))throw IOException("Pluto guide cache write failed")
                            }finally{temp.delete()}
                        }
                    }
                    parseGuide(plutoFile,channels,now,exact){cancellation.ensureActive()}
                }
            }catch(e:CancellationException){throw e}catch(_:Exception){emptyList()}
            val providerChannels=pluto.map{it.channel}.toSet()
            val result=primary.filterNot{it.channel in providerChannels}+pluto
            val a=JSONArray();result.forEach{a.put(JSONArray().put(it.channel).put(it.start).put(it.end).put(it.title))}
            val obj=JSONObject().put("signature",signature).put("fetched",compressed.lastModified()).put("programs",a)
            write(file,obj.toString());saved(obj,false)
        }catch(e:CancellationException){throw e}catch(e:Exception){if(cached?.optString("signature")==signature)saved(cached,true) else throw e}
    }
    private fun parseGuide(file:File,channels:List<JaLiveChannel>,now:Long,providerAliases:Map<String,String>,checkActive:()->Unit):List<JaLiveProgram> {
        val ids=channels.associateBy{it.id}
        val bases=channels.groupBy{JaLiveTvRules.baseId(it.id)}
        val names=if(providerAliases.isNotEmpty())emptyMap() else channels.flatMap{channel -> JaLiveTvRules.guideKeys(channel.id,channel.name).map{it to channel}}.groupBy({it.first},{it.second})
        val aliases=providerAliases.toMutableMap();val guideNames=mutableMapOf<String,MutableSet<String>>()
        val candidates=mutableMapOf<String,MutableSet<String>>()
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
                        names[key]?.singleOrNull()?.let{candidates.getOrPut(guideId){mutableSetOf()}.add(it.id)}
                    }
                    "programme"->{
                        if(!schedule){
                            candidates.forEach{(id,targets)->if(id !in aliases&&targets.size==1){
                                val keys=guideNames.filterValues{id in it}.keys
                                if(keys.any{guideNames[it]?.size==1})aliases[id]=targets.first()
                            }};schedule=true
                        }
                        programId=aliases[parser.getAttributeValue(null,"channel")]?:""
                        start=JaLiveTvRules.xmlTime(parser.getAttributeValue(null,"start")?:"")
                        end=JaLiveTvRules.xmlTime(parser.getAttributeValue(null,"stop")?:"");title=""
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
