package com.example.ja

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.WebExtension
import java.util.UUID

/** Optional, session-bound VidFast adapter. No network probes or player-DOM replacement. */
internal class JaVidFastBridge(private val url: String, private val resumeMs: Long, private val restart: Boolean) {
    var onStarted: (() -> Unit)?=null
    var onProgress: ((Long,Long)->Unit)?=null
    private val handler=Handler(Looper.getMainLooper())
    private val token=UUID.randomUUID().toString()
    private var connected=true
    private var port: WebExtension.Port?=null
    private var lastState=0L
    private var lastProgress=0L
    private var lastPosition=-1L
    private var lastDuration=-1L
    private var resumeApplied=false
    private val timeline=JaVidFastTimeline()
    private val path=Uri.parse(url).pathSegments
    private val type=path.firstOrNull().orEmpty()
    private val mediaId=path.getOrNull(1)?.toIntOrNull() ?: 0
    private val season=if(type=="tv")path.getOrNull(2)?.toIntOrNull() ?: 0 else 0
    private val episode=if(type=="tv")path.getOrNull(3)?.toIntOrNull() ?: 0 else 0

    fun attach(runtime: GeckoRuntime, session: GeckoSession,
        install: (GeckoRuntime,String)->GeckoResult<WebExtension>, ready: (Boolean)->Unit) {
        try {
            install(runtime,"resource://android/assets/ja_vidfast_api/").accept({ extension ->
                if(!connected)return@accept
                if(extension==null){ready(false);return@accept}
                try {
                    session.webExtensionController.setMessageDelegate(extension,object:WebExtension.MessageDelegate {
                        override fun onConnect(incoming: WebExtension.Port) {
                            val sender=incoming.sender
                            val origin=Uri.parse(sender.url)
                            if(!connected || sender.session !== session || !sender.isTopLevel() || origin.scheme!="https" || origin.host!="vidfast.vc") {
                                incoming.disconnect();return
                            }
                            port=incoming
                            incoming.setDelegate(object:WebExtension.PortDelegate {
                                override fun onPortMessage(message: Any, from: WebExtension.Port) {
                                    val data=message as? JSONObject ?: return
                                    handler.post {
                                        if(!connected || port !== from || data.optString("token")!=token)return@post
                                        when(data.optString("event")) {
                                            "STATE" -> state(data)
                                            "INVALIDATED" -> { lastState=0L;Log.i("JA_VIDFAST","STATE_INVALIDATED") }
                                            "CONTROL_RESULT" -> Log.i("JA_VIDFAST","CONTROL_RESULT action=${data.optString("action")} result=${data.optString("result")}")
                                            "RESUME_RESULT" -> Log.i("JA_VIDFAST","RESUME_RESULT applied=${data.optBoolean("applied")}")
                                        }
                                    }
                                }
                                override fun onDisconnect(from: WebExtension.Port) {
                                    handler.post { if(port===from){port=null;lastState=0L} }
                                }
                            })
                            incoming.postMessage(JSONObject().put("action","configure").put("token",token)
                                .put("tmdbId",mediaId).put("mediaType",type).put("season",season).put("episode",episode)
                                .put("positionMs",resumeMs.coerceAtLeast(0)).put("restart",restart))
                        }
                    },"ja_vidfast_api")
                    Log.i("JA_VIDFAST","API_DELEGATE_READY tmdb=$mediaId type=$type season=$season episode=$episode")
                    ready(true)
                }catch(e:Exception){Log.w("JA_VIDFAST","OPTIONAL_ADAPTER_FAILED",e);ready(false)}
            }, { error -> Log.w("JA_VIDFAST","OPTIONAL_ADAPTER_INSTALL_FAILED",error);ready(false) })
        }catch(e:Exception){Log.w("JA_VIDFAST","OPTIONAL_ADAPTER_UNAVAILABLE",e);ready(false)}
    }
    private fun state(data: JSONObject) {
        if(data.optInt("tmdbId")!=mediaId || data.optString("mediaType")!=type ||
            (type=="tv" && (data.optInt("season")!=season || data.optInt("episode")!=episode)))return
        val position=data.optDouble("currentTime",Double.NaN)
        val duration=data.optDouble("duration",Double.NaN)
        if(!position.isFinite() || !duration.isFinite() || duration<=0 || position<0 || position>duration+2 || duration*1000>9_007_199_254_740_991.0)return
        val now=SystemClock.elapsedRealtime()
        lastState=now;resumeApplied=data.optBoolean("resumeApplied")
        if(!resumeApplied)return
        if(timeline.sample(now,position,duration,data.optBoolean("playing"),data.optString("playerEvent")=="seeked")) {
            Log.i("JA_VIDFAST","API_TIME_ADVANCING tmdb=$mediaId")
            onStarted?.invoke()
        }
        if(!timeline.confirmed())return
        val positionMs=(position*1000).toLong();val durationMs=(duration*1000).toLong()
        val force=data.optString("playerEvent") in setOf("pause","seeked","ended")
        if((force || now-lastProgress>=5000) && (positionMs!=lastPosition || durationMs!=lastDuration)) {
            lastProgress=now;lastPosition=positionMs;lastDuration=durationMs
            onProgress?.invoke(positionMs,durationMs)
        }
    }
    fun ownsProgress(): Boolean = connected && timeline.confirmed() && resumeApplied && lastState>0 && SystemClock.elapsedRealtime()-lastState<6500
    fun send(action: String): Boolean {
        if(action !in setOf("toggle","back10","forward10") || !connected || lastState==0L || SystemClock.elapsedRealtime()-lastState>5000)return false
        val active=port ?: return false
        return try { active.postMessage(JSONObject().put("action","control").put("control",action).put("token",token));true }
        catch(_:Exception){false}
    }
    fun clear() {
        connected=false;handler.removeCallbacksAndMessages(null)
        try{port?.disconnect()}catch(_:Exception){}
        port=null;onStarted=null;onProgress=null
    }
    companion object {
        /** Caller freezes this URL per source session; progress must not reload the player. */
        fun resumeUrl(url: String, positionMs: Long): String {
            val uri=Uri.parse(url)
            if(uri.scheme!="https" || uri.host!="vidfast.vc")return url
            val out=uri.buildUpon().clearQuery()
            uri.queryParameterNames.filter { it!="startAt" }.forEach { key ->
                uri.getQueryParameters(key).forEach { value -> out.appendQueryParameter(key,value) }
            }
            return out.appendQueryParameter("startAt",(positionMs.coerceAtLeast(0)/1000.0).toString()).build().toString()
        }
    }
}
