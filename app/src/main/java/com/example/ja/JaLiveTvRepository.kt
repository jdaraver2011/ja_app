package com.example.ja

import android.content.Context
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
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

internal data class JaLiveChannel(val id:String,val name:String,val logo:String,val urls:List<String>)
internal data class JaLivePlaylist(val code:String,val name:String,val distanceKm:Double=0.0) {
    val url:String get()=if(code=="us")"https://iptv-org.github.io/iptv/countries/us.m3u" else "https://iptv-org.github.io/iptv/cities/$code.m3u"
}
internal data class JaLiveProgram(val channel:String,val start:Long,val end:Long,val title:String)
internal data class JaLiveChannels(val channels:List<JaLiveChannel>,val saved:Boolean)
internal data class JaLiveGuide(val programs:List<JaLiveProgram>,val fetched:Long,val saved:Boolean)

internal class JaLiveTvRepository(private val context:Context) {
    private val root=File(context.cacheDir,"live-tv").apply{mkdirs()}
    private val client=OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS)
        .callTimeout(120,TimeUnit.SECONDS).build()
    private fun request(url:String)=Request.Builder().url(url).header("User-Agent","JA/2.4.1 (https://github.com/jdaraver2011/ja_app)").build()
    private fun text(url:String,limit:Long):String = client.newCall(request(url)).execute().use { r ->
        if(!r.isSuccessful)throw IOException("HTTP ${r.code}")
        val body=r.body?:throw IOException("Empty response")
        LimitedInput(body.byteStream(),limit).bufferedReader().use{it.readText()}
    }
    suspend fun nearest(zip:String):List<JaLivePlaylist> = withContext(Dispatchers.IO) {
        if(!JaWeatherRules.validZip(zip))return@withContext emptyList()
        val file=File(root,"zip-$zip.json")
        val location=if(file.exists())JSONObject(file.readText()) else JSONObject(text("https://api.zippopotam.us/us/$zip",64_000)).also{write(file,it.toString())}
        val p=location.getJSONArray("places").getJSONObject(0)
        val lat=p.getString("latitude").toDouble();val lon=p.getString("longitude").toDouble()
        val data=JSONArray(context.assets.open("live-tv/cities.json").bufferedReader().use{it.readText()})
        (0 until data.length()).map{data.getJSONObject(it)}.map{
            JaLivePlaylist(it.getString("code"),"${it.getString("name")}, ${it.getString("state")}",
                JaLiveTvRules.distance(lat,lon,it.getDouble("lat"),it.getDouble("lon")))
        }.filter{it.distanceKm.isFinite()}.sortedWith(compareBy<JaLivePlaylist>{it.distanceKm}.thenBy{it.code}).take(3)
    }
    suspend fun channels(playlist:JaLivePlaylist,force:Boolean=false):JaLiveChannels = withContext(Dispatchers.IO) {
        require(playlist.code=="us"||Regex("us[a-z0-9]+").matches(playlist.code))
        val file=File(root,"${playlist.code}.m3u")
        if(!force&&JaLiveTvRules.fresh(file.lastModified(),System.currentTimeMillis(),86_400_000))return@withContext JaLiveChannels(parsePlaylist(file.readText()),false)
        try {
            val raw=text(playlist.url,4_000_000)
            val parsed=parsePlaylist(raw)
            if(parsed.isEmpty())throw IOException("No channels in playlist")
            write(file,raw);JaLiveChannels(parsed,false)
        }catch(e:Exception){if(file.exists())JaLiveChannels(parsePlaylist(file.readText()),true) else throw e}
    }
    private fun parsePlaylist(raw:String):List<JaLiveChannel> {
        require(raw.trimStart().startsWith("#EXTM3U"))
        val channels=linkedMapOf<String,JaLiveChannel>();var info:String?=null
        raw.lineSequence().map{it.trim()}.forEach{line ->
            if(line.startsWith("#EXTINF:"))info=line
            else if(line.isNotBlank()&&!line.startsWith("#")) {
                val item=info;info=null
                if(item!=null&&webUrl(line)) {
                    val name=JaLiveTvRules.name(item)
                    val id=JaLiveTvRules.attribute(item,"tvg-id").ifBlank{line}
                    if(name.isNotBlank()) {
                        val old=channels[id]
                        channels[id]=old?.copy(urls=(old.urls+line).distinct())?:JaLiveChannel(id,name,
                            JaLiveTvRules.attribute(item,"tvg-logo").takeIf{webUrl(it)}?:"",listOf(line))
                    }
                }
            }
        }
        return channels.values.sortedBy{it.name.lowercase()}
    }
    // One compressed download per six hours; keep only matching channel schedules for 48 hours.
    suspend fun guide(channels:List<JaLiveChannel>,force:Boolean=false):JaLiveGuide = withContext(Dispatchers.IO) {
        val cancellation=currentCoroutineContext()
        val now=System.currentTimeMillis()
        val wanted=channels.map{it.id}.toSet()
        val signature=java.security.MessageDigest.getInstance("SHA-256").digest(wanted.sorted().joinToString("\n").toByteArray()).joinToString(""){"%02x".format(it)}
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
            val result=parseGuide(compressed,channels,now){cancellation.ensureActive()}
            val a=JSONArray();result.forEach{a.put(JSONArray().put(it.channel).put(it.start).put(it.end).put(it.title))}
            val obj=JSONObject().put("signature",signature).put("fetched",compressed.lastModified()).put("programs",a)
            write(file,obj.toString());saved(obj,false)
        }catch(e:CancellationException){throw e}catch(e:Exception){if(cached?.optString("signature")==signature)saved(cached,true) else throw e}
    }
    private fun parseGuide(file:File,channels:List<JaLiveChannel>,now:Long,checkActive:()->Unit):List<JaLiveProgram> {
        val ids=channels.associateBy{it.id}
        val bases=channels.groupBy{JaLiveTvRules.baseId(it.id)}
        val names=channels.groupBy{JaLiveTvRules.guideName(it.name)}
        val aliases=mutableMapOf<String,String>();val guideNames=mutableMapOf<String,MutableSet<String>>()
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
