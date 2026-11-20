package com.example.ja

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URLConnection

internal const val JA_WEATHER_CHANNEL="ja.weatherstar.local"
internal fun jaWeatherChannel()=JaLiveChannel(JA_WEATHER_CHANNEL,"1 · JA Local Weather","",emptyList(),categories=setOf("weather"))
internal fun jaWeatherPrograms(now:Long,zip:String):List<JaLiveProgram>{
    val start=now/1_800_000*1_800_000
    return (0..48).map{ val t=start+it*1_800_000L;JaLiveProgram(JA_WEATHER_CHANNEL,t,t+1_800_000,"Local Weather${if(zip.isNotBlank()) " · $zip" else ""}") }
}

/** Bundled static client, served on an HTTPS asset origin; no arbitrary JS/native bridge. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun JaWeatherStarView(state:JaWeatherState,modifier:Modifier=Modifier,onReady:()->Unit={}){
    val context=LocalContext.current
    val owner=LocalLifecycleOwner.current
    var view by remember{mutableStateOf<WebView?>(null)}
    var resumed by remember{mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))}
    var failed by remember{mutableStateOf(false)}
    val ready by rememberUpdatedState(onReady)
    val data=state.data?.takeIf{it.zip==state.zip}
    val url=remember(state.zip,data?.latitude,data?.longitude,state.revision){
        Uri.Builder().scheme("https").authority("appassets.androidplatform.net").path("/weatherstar/index.html")
            .appendQueryParameter("viewMode","wide-enhanced").appendQueryParameter("kiosk","true")
            .appendQueryParameter("scanLines","false").appendQueryParameter("refreshTime","900000")
            .appendQueryParameter("mediaPlaying","false")
            .appendQueryParameter("latLonQuery",data?.place?:state.zip)
            .apply{if(data!=null&&data.latitude.isFinite()&&data.longitude.isFinite())
                appendQueryParameter("latLon",JSONObject().put("lat",data.latitude).put("lon",data.longitude).toString())}
            .appendQueryParameter("jaRevision",state.revision.toString()).build().toString()
    }
    val audio=remember{context.getSystemService(Context.AUDIO_SERVICE) as AudioManager}
    DisposableEffect(state.sound,resumed,state.zip){
        val request=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setOnAudioFocusChangeListener{if(it<=AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)state.changeSound(false)}.build()
        if(state.sound&&resumed&&JaWeatherRules.validZip(state.zip)&&audio.requestAudioFocus(request)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED)state.changeSound(false)
        onDispose{audio.abandonAudioFocusRequest(request)}
    }
    DisposableEffect(owner){
        val observer=LifecycleEventObserver{_,_->
            resumed=owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if(resumed)view?.onResume() else {view?.evaluateJavascript("window.jaWeatherSound?.(false,0)",null);view?.onPause()}
        };owner.lifecycle.addObserver(observer)
        onDispose{owner.lifecycle.removeObserver(observer);view?.apply{evaluateJavascript("window.jaWeatherSound?.(false,0)",null);stopLoading();destroy()};view=null}
    }
    val soundScript="""
        (()=>{
          window.jaWeatherSound=(enabled,volume)=>{
            window.jaWeatherAudio={enabled,volume};
            const b=document.getElementById('ToggleMedia');
            if(b&&((b.getAttribute('aria-pressed')==='true')!==enabled))b.click();
            document.querySelectorAll('audio').forEach(a=>{a.muted=!enabled;a.volume=volume;});
            const slider=document.querySelector('#volume-slider input');
            if(enabled&&slider&&Number(slider.value)!==Math.round(volume*100)){slider.value=Math.max(1,Math.round(volume*100));slider.dispatchEvent(new Event('input',{bubbles:true}));}
          };
          if(!window.jaWeatherAudioTimer)window.jaWeatherAudioTimer=setInterval(()=>{
            const a=window.jaWeatherAudio;if(a)window.jaWeatherSound(a.enabled,a.volume);
          },2000);
        })();
    """.trimIndent()
    if(!JaWeatherRules.validZip(state.zip)){
        Box(modifier.background(Color(0xFF122B70))){Text("Set a preferred ZIP code in Settings to start local weather.",Modifier.padding(16.dp),color=Color.White)}
        return
    }
    Box(modifier.background(Color.Black)){
        AndroidView(factory={ctx->WebView(ctx).apply{
            setBackgroundColor(android.graphics.Color.BLACK);keepScreenOn=true
            settings.javaScriptEnabled=true;settings.domStorageEnabled=true
            settings.userAgentString=settings.userAgentString+" JA/2.4.9 (+https://github.com/jdaraver2011/ja_app)"
            settings.mediaPlaybackRequiresUserGesture=false
            settings.allowFileAccess=false;settings.allowContentAccess=false
            settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webChromeClient=object:WebChromeClient(){override fun onConsoleMessage(message:ConsoleMessage):Boolean{
                if(message.messageLevel()==ConsoleMessage.MessageLevel.ERROR)android.util.Log.w("JA_WEATHERSTAR",message.message().take(240));return true
            }}
            webViewClient=object:WebViewClient(){
                override fun shouldOverrideUrlLoading(v:WebView,r:WebResourceRequest)=r.url.scheme!="https"||r.url.host!="appassets.androidplatform.net"
                override fun shouldInterceptRequest(v:WebView,r:WebResourceRequest):WebResourceResponse?{
                    if(r.url.host!="appassets.androidplatform.net")return null
                    val path=r.url.path.orEmpty().removePrefix("/")
                    if(!path.startsWith("weatherstar/")||path.split('/').any{it==".."})return WebResourceResponse("text/plain","UTF-8",403,"Forbidden",emptyMap(),ByteArrayInputStream(byteArrayOf()))
                    return try{
                        val mime=when{path.endsWith(".js")->"application/javascript";path.endsWith(".css")->"text/css";path.endsWith(".json")->"application/json";path.endsWith(".woff")->"font/woff";else->URLConnection.guessContentTypeFromName(path)?:"application/octet-stream"}
                        WebResourceResponse(mime,"UTF-8",200,"OK",mapOf("Access-Control-Allow-Origin" to "*","Cache-Control" to "max-age=86400"),context.assets.open(path))
                    }catch(_:Exception){WebResourceResponse("text/plain","UTF-8",404,"Not Found",emptyMap(),ByteArrayInputStream(byteArrayOf()))}
                }
                override fun onPageFinished(v:WebView,url:String){failed=false;v.evaluateJavascript(soundScript,null);v.evaluateJavascript("window.jaWeatherSound?.(${state.sound&&resumed},${state.volume})",null);ready()}
                override fun onReceivedError(v:WebView,r:WebResourceRequest,e:WebResourceError){if(r.isForMainFrame)failed=true}
            }
            view=this;loadUrl(url)
        }},modifier=Modifier.fillMaxSize(),update={v->
            if(v.tag!=url){v.tag=url;if(v.url!=url)v.loadUrl(url)}
            v.evaluateJavascript("window.jaWeatherSound?.(${state.sound&&resumed},${state.volume})",null)
        },onRelease={v->if(view===v){view=null;v.keepScreenOn=false;v.stopLoading();v.destroy()}})
        if(failed)Text("Weather presentation unavailable. Use Refresh to retry.",Modifier.padding(12.dp),color=Color.White)
    }
}

@Composable
internal fun JaWeatherStarPanel(state:JaWeatherState,modifier:Modifier=Modifier,onFullscreen:()->Unit,
    onChannel:(Int)->Unit={},showChannels:Boolean=false,onReady:()->Unit={},onWeatherChannel:(()->Unit)?=null){
    var menu by remember{mutableStateOf<String?>(null)}
    Column(modifier.background(Color(0xFF101B30))){
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(4.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)){
            TvFocusButton(onClick={state.revision++}){Text("↻",fontSize=18.sp)}
            if(onWeatherChannel!=null)TvFocusButton(onClick=onWeatherChannel){Text("Channel 1")}
            TvFocusButton(onClick={menu="Location"}){Text("Location")}
            TvFocusButton(onClick={menu="Sound"}){Text("Sound")}
            TvFocusButton(onClick={menu="Alerts"}){Text("Alerts${if(state.data?.alerts?.isNotEmpty()==true) " · ${state.data?.alerts?.size}" else ""}")}
            TvFocusButton(onClick=onFullscreen){Text("Fullscreen")}
        }
        JaWeatherStarView(state,Modifier.fillMaxWidth().weight(1f),onReady)
        if(showChannels)Row(Modifier.fillMaxWidth().padding(4.dp),horizontalArrangement=Arrangement.Center){
            TvFocusButton(onClick={onChannel(-1)}){Text("Previous channel")}
            Spacer(Modifier.width(8.dp))
            TvFocusButton(onClick={onChannel(1)}){Text("Next channel")}
        }
    }
    menu?.let{kind->AlertDialog(onDismissRequest={menu=null},title={Text(kind)},confirmButton={TvFocusButton(onClick={menu=null}){Text("Done")}},text={
        Column(Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            when(kind){
                "Location"->JaWeatherSettings(state)
                "Sound"->{
                    Row{Checkbox(checked=state.sound,onCheckedChange={state.changeSound(it)});Text("Weather music")}
                    Text("Volume: ${(state.volume*100).toInt()}%")
                    Slider(value=state.volume,onValueChange={state.changeVolume(it)},valueRange=0f..1f)
                    Row{TvFocusButton(onClick={state.changeVolume((state.volume-0.1f).coerceAtLeast(0f))}){Text("−")};TvFocusButton(onClick={state.changeVolume((state.volume+0.1f).coerceAtMost(1f))}){Text("+")}}
                    Text("Sunny Afternoon — netbymatt, Latest Observations (CC BY 4.0).",fontSize=12.sp)
                }
                else->{val alerts=state.data?.alerts.orEmpty();if(alerts.isEmpty())Text("No active alerts in the latest local weather update.")
                    alerts.forEach{Text(it.event);Text(it.headline);Text(it.instruction.ifBlank{it.description})}}
            }
        }
    })}
}

@Composable
internal fun JaWeatherStarPage(state:JaWeatherState,onClose:()->Unit,onChannel:()->Unit){
    BackHandler{onClose()}
    var full by remember{mutableStateOf(false)}
    if(full)Dialog(onDismissRequest={full=false},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
        BackHandler{full=false}
        JaWeatherStarPanel(state,Modifier.fillMaxSize(),onFullscreen={full=false},onWeatherChannel=onChannel)
    }else Column(Modifier.fillMaxSize().background(Color(0xFF101B30)).padding(10.dp)){
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Text("JA Local Weather · ${state.zip}",Modifier.weight(1f),color=Color.White,fontSize=20.sp)
        }
        JaWeatherStarPanel(state,Modifier.fillMaxWidth().weight(1f),onFullscreen={full=true},onWeatherChannel=onChannel)
    }
}
