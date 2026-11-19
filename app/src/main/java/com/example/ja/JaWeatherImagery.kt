package com.example.ja

import android.content.Context
import android.util.Xml
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.StringReader
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

internal data class JaWeatherFrame(val time: String, val file: File)
internal data class JaWeatherLoop(val frames: List<JaWeatherFrame>, val base: File?, val legend: File?, val markerX: Float, val markerY: Float)
internal class JaWeatherImageryRepository(context: Context) {
    private val directory = File(context.cacheDir, "weather-imagery").apply { mkdirs() }
    private val client = OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS)
        .readTimeout(20,TimeUnit.SECONDS).callTimeout(25,TimeUnit.SECONDS).build()
    private val permits = Semaphore(3)
    private fun bytes(url: String): ByteArray {
        require(java.net.URI(url).host in listOf("nowcoast.noaa.gov","basemap.nationalmap.gov"))
        client.newCall(Request.Builder().url(url).header("User-Agent","JA/2.3.5 (https://github.com/jdaraver2011/ja_app)").build()).execute().use { r ->
            check(r.isSuccessful) { "Imagery service unavailable" }
            val b = r.body?.bytes() ?: error("Empty imagery response")
            check(b.size <= 3 * 1024 * 1024) { "Imagery response too large" }
            return b
        }
    }
    private fun image(url: String): File {
        val name = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(Locale.US,it) }
        val file = File(directory,"$name.png")
        if (!file.exists()) {
            val b = bytes(url)
            check(b.size > 8 && b[0] == 0x89.toByte() && b[1] == 0x50.toByte() && b[2] == 0x4e.toByte() && b[3] == 0x47.toByte()) { "Invalid image response" }
            val temporary = File(directory,"$name.part")
            temporary.writeBytes(b)
            check(temporary.renameTo(file))
        }
        file.setLastModified(System.currentTimeMillis())
        return file
    }
    suspend fun load(data: JaWeatherData): JaWeatherLoop = withContext(Dispatchers.IO) {
        require(data.latitude.isFinite() && data.longitude.isFinite())
        val files = directory.listFiles().orEmpty().sortedByDescending { it.lastModified() }
        var size = 0L
        files.forEach { size += it.length(); if (size > 128L*1024*1024 || it.lastModified() < System.currentTimeMillis()-86400000) it.delete() }
        val layer = JaWeatherMediaRules.radarLayer(data.latitude,data.longitude)
        val endpoint = "https://nowcoast.noaa.gov/geoserver/observations/weather_radar/ows"
        val xml = String(bytes("$endpoint?service=WMS&request=GetCapabilities&version=1.1.1"), Charsets.UTF_8)
            .replace(Regex("<!DOCTYPE[^>]*>"), "")
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        var depth = -1
        var selected = false
        var csv = ""
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "Layer") { depth = parser.depth; selected = false }
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "Name" && parser.depth == depth+1) selected = parser.nextText() == layer
            if (selected && parser.eventType == XmlPullParser.START_TAG && parser.name == "Extent" && parser.getAttributeValue(null,"name") == "time") csv = parser.nextText()
            parser.next()
        }
        val times = JaWeatherMediaRules.frames(csv,System.currentTimeMillis())
        check(times.size >= 2) { "Animation history unavailable" }
        val bounds = JaWeatherMediaRules.bounds(data.latitude,data.longitude,false)
        val base = runCatching {
            image("https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/export".toHttpUrl().newBuilder()
                .addQueryParameter("bbox",bounds).addQueryParameter("bboxSR","4326").addQueryParameter("imageSR","4326")
                .addQueryParameter("size","640,400").addQueryParameter("format","png32").addQueryParameter("f","image").build().toString())
        }.getOrNull()
        val frames = coroutineScope { times.map { time -> async {
            permits.withPermit {
                ensureActive()
                try {
                    val url = endpoint.toHttpUrl().newBuilder().addQueryParameter("service","WMS").addQueryParameter("version","1.1.1")
                        .addQueryParameter("request","GetMap").addQueryParameter("layers",layer).addQueryParameter("styles","")
                        .addQueryParameter("srs","EPSG:4326").addQueryParameter("bbox",bounds).addQueryParameter("width","640")
                        .addQueryParameter("height","400").addQueryParameter("format","image/png").addQueryParameter("transparent","true")
                        .addQueryParameter("time",time).build().toString()
                    JaWeatherFrame(time,image(url))
                } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            }
        } }.awaitAll().filterNotNull() }
        check(frames.size >= 2) { "Animation frames unavailable" }
        val legend = runCatching { image(endpoint.toHttpUrl().newBuilder().addQueryParameter("service","WMS")
            .addQueryParameter("version","1.1.1").addQueryParameter("request","GetLegendGraphic")
            .addQueryParameter("layer",layer).addQueryParameter("format","image/png").build().toString()) }.getOrNull()
        val edges = bounds.split(",").map { it.toDouble() }
        JaWeatherLoop(frames,base,legend,((data.longitude-edges[0])/(edges[2]-edges[0])).toFloat(),
            ((edges[3]-data.latitude)/(edges[3]-edges[1])).toFloat())
    }
}

@Composable
internal fun rememberWeatherResumed(): Boolean {
    val owner = LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ -> resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return resumed
}
private fun frameTime(time: String) = runCatching {
    DateTimeFormatter.ofPattern("MM-dd hh:mm a",Locale.US).withZone(ZoneId.systemDefault()).format(Instant.parse(time))
}.getOrDefault("Unavailable")

@Composable
internal fun JaWeatherRadarWindow(data: JaWeatherData, revision: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val repository = remember { JaWeatherImageryRepository(context) }
    val resumed = rememberWeatherResumed()
    var radar by remember(data.zip) { mutableStateOf<JaWeatherLoop?>(null) }
    var radarError by remember(data.zip) { mutableStateOf(false) }
    var enlarged by remember(data.zip) { mutableStateOf(false) }
    var focus by remember { mutableStateOf(false) }
    LaunchedEffect(data.zip,data.fetchedAt,revision,resumed) {
        if(!resumed) return@LaunchedEffect
        try { radar=repository.load(data);radarError=false }
        catch(e:CancellationException){throw e} catch(_:Exception){radarError=true}
    }
    Column(modifier.onFocusChanged{focus=it.isFocused}
        .border(if(focus)2.dp else 1.dp,if(focus)Color.White else Color.Gray,RoundedCornerShape(5.dp))
        .clickable(enabled=radar!=null){enlarged=true}.padding(4.dp),
        horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(3.dp)) {
        val loop=radar
        if(loop==null) Text("Radar",color=Color.White,fontSize=13.sp,lineHeight=16.sp)
        if(loop==null) Box(Modifier.fillMaxWidth().weight(1f),contentAlignment=Alignment.Center) {
            Text(if(radarError)"Radar unavailable" else "Loading six-hour history…",color=Color.LightGray,fontSize=12.sp)
        } else RadarLoopPanel(loop,Modifier.fillMaxWidth().weight(1f),resumed && !enlarged,preview=true)
        Text(if(radarError)"Saved radar · Select to expand" else "Six-hour history · Select to expand",color=Color.LightGray,fontSize=10.sp,lineHeight=12.sp)
    }
    val loop=radar
    if(enlarged && loop!=null) Dialog(onDismissRequest={enlarged=false},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        BackHandler{enlarged=false}
        var playing by remember{mutableStateOf(true)}
        var step by remember{mutableIntStateOf(0)}
        var zoom by remember{mutableFloatStateOf(1f)}
        val firstFocus=remember{FocusRequester()}
        LaunchedEffect(Unit){firstFocus.requestFocus()}
        Column(Modifier.fillMaxSize().background(Color(0xFF111725)).padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text("Radar · ${data.place}",color=Color.White,fontSize=20.sp,lineHeight=24.sp)
            Text("${frameTime(loop.frames.first().time)} → ${frameTime(loop.frames.last().time)} · Six-hour window",color=Color.LightGray,fontSize=11.sp,lineHeight=14.sp)
            RadarLoopPanel(loop,Modifier.fillMaxWidth().weight(1f),resumed && playing,step,zoom)
            Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) {
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                    TvFocusButton(onClick={playing=!playing},modifier=Modifier.focusRequester(firstFocus)){Text(if(playing)"Pause" else "Play")}
                    TvFocusButton(onClick={playing=false;step--}){Text("Previous")}
                    TvFocusButton(onClick={playing=false;step++}){Text("Next")}
                    TvFocusButton(onClick={zoom=(zoom+0.5f).coerceAtMost(3f)}){Text("Zoom +")}
                    TvFocusButton(onClick={zoom=(zoom-0.5f).coerceAtLeast(1f)}){Text("Zoom −")}
                    TvFocusButton(onClick={enlarged=false}){Text("Close")}
                }
            }
        }
    }
}

/** Metadata has its own header, leaving the map unobstructed at every size. */
@Composable
private fun RadarLoopPanel(loop:JaWeatherLoop,modifier:Modifier,running:Boolean,step:Int=0,zoom:Float=1f,preview:Boolean=false) {
    var index by remember(loop){mutableIntStateOf(0)}
    LaunchedEffect(loop,running){while(running && isActive){delay(if(index==loop.frames.lastIndex)1200 else 400);index=(index+1)%loop.frames.size}}
    var previousStep by remember(loop){mutableIntStateOf(step)}
    LaunchedEffect(loop,step){val delta=step-previousStep;previousStep=step;index=Math.floorMod(index+delta,loop.frames.size)}
    val frame=loop.frames[index.coerceIn(0,loop.frames.lastIndex)]
    Column(modifier,verticalArrangement=Arrangement.spacedBy(4.dp)) {
        if(preview) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.Top) {
            Text("Radar · ${frameTime(frame.time)} · ${loop.frames.size} frames",color=Color.White,fontSize=11.sp,lineHeight=14.sp,
                modifier=Modifier.weight(1f),maxLines=2)
            Text("NOAA · USGS map",color=Color.LightGray,fontSize=10.sp,lineHeight=14.sp,maxLines=1)
        } else BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide=maxWidth>=600.dp
            val legendWidth=minOf(320.dp,maxWidth*0.45f)
            if(wide) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.Top) {
                Text("${frameTime(frame.time)} · Frame ${index+1}/${loop.frames.size}${if(zoom>1f)" · ${zoom}×" else ""}",
                    color=Color.White,fontSize=13.sp,lineHeight=17.sp,modifier=Modifier.weight(1f),maxLines=2)
                RadarLegend(loop,Modifier.width(legendWidth))
            } else Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text("${frameTime(frame.time)} · Frame ${index+1}/${loop.frames.size}",color=Color.White,fontSize=12.sp,lineHeight=16.sp)
                RadarLegend(loop,Modifier.fillMaxWidth())
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).clipToBounds().background(Color(0xFF172230)),contentAlignment=Alignment.Center) {
            // The small wide preview crops equally above/below; fullscreen fits the complete map.
            val mapWidth=if(preview)maxOf(maxWidth,maxHeight*1.6f) else minOf(maxWidth,maxHeight*1.6f)
            Box(Modifier.requiredSize(mapWidth,mapWidth/1.6f).graphicsLayer(scaleX=zoom,scaleY=zoom)) {
                loop.base?.let{AsyncImage(it,null,Modifier.fillMaxSize(),contentScale=ContentScale.FillBounds)}
                AsyncImage(frame.file,null,Modifier.fillMaxSize(),contentScale=ContentScale.FillBounds)
                Canvas(Modifier.fillMaxSize()) {
                    val position=Offset(size.width*loop.markerX,size.height*loop.markerY)
                    drawCircle(Color.White,6.dp.toPx(),position);drawCircle(Color.Magenta,3.dp.toPx(),position)
                }
            }
        }
    }
}

@Composable
private fun RadarLegend(loop:JaWeatherLoop,modifier:Modifier) {
    Column(modifier,horizontalAlignment=Alignment.End,verticalArrangement=Arrangement.spacedBy(2.dp)) {
        Text("NOAA · USGS map",color=Color.LightGray,fontSize=10.sp,lineHeight=13.sp)
        Text("Radar reflectivity",color=Color.White,fontSize=11.sp,lineHeight=14.sp)
        if(loop.legend!=null) AsyncImage(loop.legend,"Radar reflectivity color scale",
            Modifier.fillMaxWidth().aspectRatio(272f/21f).background(Color(0xFF333C48)),contentScale=ContentScale.Fit)
        else Text("Color scale unavailable",color=Color.LightGray,fontSize=11.sp)
    }
}
