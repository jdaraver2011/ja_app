package com.example.ja

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URLConnection

internal const val JA_WEATHER_CHANNEL="ja.weatherstar.local"
internal fun jaWeatherChannel()=JaLiveChannel(JA_WEATHER_CHANNEL,"JA Local Weather","",emptyList(),categories=setOf("weather"))
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
            .appendQueryParameter("jaKiosk","true").appendQueryParameter("scanLines","false").appendQueryParameter("refreshTime","900000")
            .appendQueryParameter("mediaPlaying","false")
            .appendQueryParameter("latLonQuery",data?.place?:state.zip)
            .apply{if(data!=null&&data.latitude.isFinite()&&data.longitude.isFinite())
                appendQueryParameter("latLon",JSONObject().put("lat",data.latitude).put("lon",data.longitude).toString())}
            .appendQueryParameter("jaRevision",state.revision.toString()).build().toString()
    }
    DisposableEffect(owner){
        val observer=LifecycleEventObserver{_,_->
            resumed=owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if(resumed)view?.onResume() else {view?.onPause()}
        };owner.lifecycle.addObserver(observer)
        onDispose{owner.lifecycle.removeObserver(observer);view?.apply{stopLoading();destroy()};view=null}
    }
    if(!JaWeatherRules.validZip(state.zip)){
        Box(modifier.background(Color(0xFF122B70))){Text("Set a preferred ZIP code in Settings to start local weather.",Modifier.padding(16.dp),color=Color.White)}
        return
    }
    Box(modifier.background(Color.Black)){
        if(state.sound)JaWeatherAudio(state)
        AndroidView(factory={ctx->WebView(ctx).apply{
            setBackgroundColor(android.graphics.Color.BLACK);keepScreenOn=true
            settings.javaScriptEnabled=true;settings.domStorageEnabled=true
            settings.userAgentString=settings.userAgentString+" JA/2.5.1 (+https://github.com/jdaraver2011/ja_app)"
            settings.mediaPlaybackRequiresUserGesture=false
            settings.useWideViewPort=true;settings.loadWithOverviewMode=true
            settings.textZoom=100;settings.setSupportZoom(false)
            isVerticalScrollBarEnabled=false;isHorizontalScrollBarEnabled=false
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
                override fun onPageFinished(v:WebView,url:String){failed=false;ready()}
                override fun onReceivedError(v:WebView,r:WebResourceRequest,e:WebResourceError){if(r.isForMainFrame)failed=true}
            }
            view=this;loadUrl(url)
        }},modifier=Modifier.fillMaxSize(),update={v->
            if(v.tag!=url){v.tag=url;if(v.url!=url)v.loadUrl(url)}
        },onRelease={v->if(view===v){view=null;v.keepScreenOn=false;v.stopLoading();v.destroy()}})
        if(failed)Text("Weather presentation unavailable. Reopen weather or check your connection.",Modifier.padding(12.dp),color=Color.White)
    }
}

@Composable
internal fun JaWeatherStarPage(state:JaWeatherState,onClose:()->Unit){
    BackHandler{onClose()}
    JaLivePresentation(true,onClose){JaWeatherStarView(state,Modifier.fillMaxSize())}
}
