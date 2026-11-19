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
internal data class JaWeatherLoop(val satellite: Boolean, val frames: List<JaWeatherFrame>, val base: File?, val legend: File?, val markerX: Float, val markerY: Float)
internal class JaWeatherImageryRepository(context: Context) {
    private val directory = File(context.cacheDir, "weather-imagery").apply { mkdirs() }
    private val client = OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS)
        .readTimeout(20,TimeUnit.SECONDS).callTimeout(25,TimeUnit.SECONDS).build()
    private val permits = Semaphore(3)
    private fun bytes(url: String): ByteArray {
        require(java.net.URI(url).host in listOf("nowcoast.noaa.gov","basemap.nationalmap.gov"))
        client.newCall(Request.Builder().url(url).header("User-Agent","JA/2.3.4 (https://github.com/jdaraver2011/ja_app)").build()).execute().use { r ->
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
    suspend fun load(data: JaWeatherData, satellite: Boolean): JaWeatherLoop = withContext(Dispatchers.IO) {
        require(data.latitude.isFinite() && data.longitude.isFinite())
        val files = directory.listFiles().orEmpty().sortedByDescending { it.lastModified() }
        var size = 0L
        files.forEach { size += it.length(); if (size > 128L*1024*1024 || it.lastModified() < System.currentTimeMillis()-86400000) it.delete() }
        val service = if (satellite) "satellite" else "weather_radar"
        val layer = if (satellite) { if (data.longitude > 0) "global_longwave_imagery_mosaic" else "goes_longwave_imagery" }
            else JaWeatherMediaRules.radarLayer(data.latitude,data.longitude)
        val endpoint = "https://nowcoast.noaa.gov/geoserver/observations/$service/ows"
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
        val bounds = JaWeatherMediaRules.bounds(data.latitude,data.longitude,satellite)
        val base = if (satellite) null else runCatching {
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
        JaWeatherLoop(satellite,frames,base,legend,((data.longitude-edges[0])/(edges[2]-edges[0])).toFloat(),
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
internal fun JaWeatherAnimationWindows(data: JaWeatherData, revision: Int) {
    val context = LocalContext.current.applicationContext
    val repository = remember { JaWeatherImageryRepository(context) }
    val resumed = rememberWeatherResumed()
    var radar by remember(data.zip) { mutableStateOf<JaWeatherLoop?>(null) }
    var satellite by remember(data.zip) { mutableStateOf<JaWeatherLoop?>(null) }
    var radarError by remember(data.zip) { mutableStateOf(false) }
    var satelliteError by remember(data.zip) { mutableStateOf(false) }
    var enlarged by remember { mutableStateOf<JaWeatherLoop?>(null) }
    LaunchedEffect(data.zip,data.fetchedAt,revision,resumed) {
        if (!resumed) return@LaunchedEffect
        supervisorScope {
            launch { try { radar = repository.load(data,false); radarError = false }
                catch (e: CancellationException) { throw e } catch (_: Exception) { radarError = true } }
            launch { try { satellite = repository.load(data,true); satelliteError = false }
                catch (e: CancellationException) { throw e } catch (_: Exception) { satelliteError = true } }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (isSatellite in listOf(false,true)) {
            val loop = if (isSatellite) satellite else radar
            val error = if (isSatellite) satelliteError else radarError
            var focus by remember(isSatellite) { mutableStateOf(false) }
            Column(Modifier.weight(1f).onFocusChanged { focus = it.isFocused }
                .border(if(focus) 2.dp else 1.dp,if(focus)Color.White else Color.Gray,RoundedCornerShape(5.dp))
                .clickable(enabled = loop != null) { enlarged = loop }.padding(3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (isSatellite) "Satellite · infrared" else "Radar", color=Color.White,fontSize=11.sp,lineHeight=13.sp)
                if (loop == null) Box(Modifier.fillMaxWidth().height(88.dp),contentAlignment=Alignment.Center) {
                    Text(if(error) "Imagery unavailable" else "Loading 6h history…",color=Color.LightGray,fontSize=10.sp)
                } else {
                    WeatherLoopImage(loop,Modifier.fillMaxWidth().height(88.dp),resumed && enlarged == null)
                    Text(if(error) "Saved frames · tap to expand" else "6h requested · tap to expand",color=Color.LightGray,fontSize=9.sp,lineHeight=11.sp)
                }
            }
        }
    }
    enlarged?.let { loop ->
        val shown = (if(loop.satellite) satellite else radar) ?: loop
        Dialog(onDismissRequest={enlarged=null},properties=DialogProperties(usePlatformDefaultWidth=false)) {
            BackHandler { enlarged=null }
            var playing by remember { mutableStateOf(true) }
            var step by remember { mutableIntStateOf(0) }
            var zoom by remember { mutableFloatStateOf(1f) }
            val firstFocus=remember { FocusRequester() }
            LaunchedEffect(Unit) { firstFocus.requestFocus() }
            Column(Modifier.fillMaxSize().background(Color(0xFF111725)).padding(12.dp)) {
                Text("${if(loop.satellite) "Satellite · infrared" else "Radar"} · ${data.place}",color=Color.White,fontSize=20.sp)
                Text("${frameTime(shown.frames.first().time)} → ${frameTime(shown.frames.last().time)} · Six-hour window",color=Color.LightGray,fontSize=11.sp)
                WeatherLoopImage(shown,Modifier.fillMaxWidth().weight(1f),resumed && playing,step,zoom)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    TvFocusButton(onClick={playing=!playing},modifier=Modifier.focusRequester(firstFocus)) { Text(if(playing) "Pause" else "Play") }
                    TvFocusButton(onClick={playing=false;step--}) { Text("Previous") }
                    TvFocusButton(onClick={playing=false;step++}) { Text("Next") }
                    TvFocusButton(onClick={zoom=(zoom+0.5f).coerceAtMost(3f)}) { Text("Zoom +") }
                    TvFocusButton(onClick={zoom=(zoom-0.5f).coerceAtLeast(1f)}) { Text("Zoom −") }
                    TvFocusButton(onClick={enlarged=null}) { Text("Close") }
                }
            }
        }
    }
}

@Composable
private fun WeatherLoopImage(loop: JaWeatherLoop, modifier: Modifier, running: Boolean, step: Int=0, zoom: Float=1f) {
    var index by remember(loop) { mutableIntStateOf(0) }
    LaunchedEffect(loop,running) {
        while(running && isActive) { delay(if(index==loop.frames.lastIndex) 1200 else 400);index=(index+1)%loop.frames.size }
    }
    var previousStep by remember(loop) { mutableIntStateOf(0) }
    LaunchedEffect(step) { val delta=step-previousStep;previousStep=step;index=Math.floorMod(index+delta,loop.frames.size) }
    val frame=loop.frames[index.coerceIn(0,loop.frames.lastIndex)]
    BoxWithConstraints(modifier.clipToBounds().background(Color(0xFF172230)),contentAlignment=Alignment.Center) {
        val mapWidth = minOf(maxWidth,maxHeight*1.6f)
        Box(Modifier.width(mapWidth).aspectRatio(1.6f).graphicsLayer(scaleX=zoom,scaleY=zoom)) {
            loop.base?.let { AsyncImage(it,null,Modifier.fillMaxSize(),contentScale=ContentScale.FillBounds) }
            AsyncImage(frame.file,null,Modifier.fillMaxSize(),contentScale=ContentScale.FillBounds)
            Canvas(Modifier.fillMaxSize()) {
                val position=Offset(size.width*loop.markerX,size.height*loop.markerY)
                drawCircle(Color.White,6.dp.toPx(),position)
                drawCircle(Color.Magenta,3.dp.toPx(),position)
            }
        }
        if(maxHeight > 150.dp) loop.legend?.let { AsyncImage(it,"Image scale",Modifier.align(Alignment.CenterEnd).width(78.dp).heightIn(max=200.dp),contentScale=ContentScale.Fit) }
        Text("${frameTime(frame.time)} · ${loop.frames.size} frames",Modifier.align(Alignment.BottomStart).background(Color(0xCC000000)).padding(2.dp),color=Color.White,fontSize=10.sp)
        Text("NOAA · ${if(loop.satellite) "GOES IR" else "USGS map"}",Modifier.align(Alignment.TopEnd).background(Color(0xCC000000)).padding(2.dp),color=Color.White,fontSize=9.sp)
        if (zoom > 1f) Text("${zoom}×",Modifier.align(Alignment.TopStart),color=Color.White,fontSize=12.sp)
    }
}
