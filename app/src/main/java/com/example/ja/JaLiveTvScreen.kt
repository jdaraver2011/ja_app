package com.example.ja

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Stable
internal class JaLiveTvState(val repository:JaLiveTvRepository) {
    var selected by mutableStateOf(JaLivePlaylist("us","United States"))
    var nearest by mutableStateOf<List<JaLivePlaylist>>(emptyList())
    var locationNote by mutableStateOf<String?>(null)
    var channels by mutableStateOf<List<JaLiveChannel>>(emptyList())
    var guide by mutableStateOf<JaLiveGuide?>(null)
    var loading by mutableStateOf(false)
    var guideLoading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var guideError by mutableStateOf<String?>(null)
    var revision by mutableIntStateOf(0)
    var loadedCode by mutableStateOf("")
    var saved by mutableStateOf(false)
    var query by mutableStateOf("")
    var returnChannel by mutableStateOf<String?>(null)
    val list=LazyListState()
    fun select(value:JaLivePlaylist){if(value.code!=selected.code){selected=value;channels=emptyList();guide=null;query="";returnChannel=null;loadedCode=""}}
}

@Composable
internal fun JaLiveTvScreen(state:JaLiveTvState,zip:String,onPlay:(JaLiveChannel)->Unit,modifier:Modifier=Modifier) {
    val owner=LocalLifecycleOwner.current
    var resumed by remember{mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))}
    DisposableEffect(owner){val observer=LifecycleEventObserver{_,_->resumed=owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)}
        owner.lifecycle.addObserver(observer);onDispose{owner.lifecycle.removeObserver(observer)}}
    LaunchedEffect(zip){
        state.nearest=emptyList()
        state.locationNote=if(JaWeatherRules.validZip(zip))"Finding nearby playlists…" else "Set Preferred ZIP code in Settings for three nearby city playlists."
        if(JaWeatherRules.validZip(zip))try{
            state.nearest=state.repository.nearest(zip)
            state.locationNote="Nearest city playlists for $zip · distances are approximate"
            if(state.selected.code!="us"&&state.nearest.none{it.code==state.selected.code})state.select(JaLivePlaylist("us","United States"))
        }catch(e:CancellationException){throw e}catch(_:Exception){state.locationNote="Nearby playlists unavailable. United States is still available."}
    }
    LaunchedEffect(state.selected.code,state.revision,resumed){
        if(!resumed)return@LaunchedEffect
        val code=state.selected.code
        state.loading=true;state.error=null
        try{
            val result=state.repository.channels(state.selected,state.revision>0)
            state.channels=result.channels;state.saved=result.saved;state.loadedCode=code
        }catch(e:CancellationException){throw e}catch(_:Exception){state.error="Could not load channels. Check your connection and retry."}
        finally{state.loading=false}
    }
    LaunchedEffect(state.channels,state.revision,resumed){
        if(!resumed||state.channels.isEmpty())return@LaunchedEffect
        while(isActive){
            state.guideLoading=true;state.guideError=null
            try{state.guide=state.repository.guide(state.channels)}catch(e:CancellationException){throw e}
            catch(_:Exception){state.guideError="Guide unavailable. Channels can still be played."}
            finally{state.guideLoading=false}
            delay(21_600_000)
        }
    }
    var now by remember{mutableLongStateOf(System.currentTimeMillis())}
    LaunchedEffect(resumed){if(resumed)while(isActive){now=System.currentTimeMillis();delay(30_000)}}
    val guideByChannel=remember(state.guide){state.guide?.programs?.groupBy{it.channel}.orEmpty()}
    val visible=remember(state.channels,state.query){state.channels.filter{it.name.contains(state.query,true)}}
    val returnFocus=remember{FocusRequester()}
    LaunchedEffect(state.returnChannel,visible){
        val id=state.returnChannel?:return@LaunchedEffect
        val index=visible.indexOfFirst{it.id==id}
        if(index>=0){state.list.scrollToItem(index);withFrameNanos{};withFrameNanos{};runCatching{returnFocus.requestFocus()};state.returnChannel=null}
    }
    Column(modifier,verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
            (listOf(JaLivePlaylist("us","United States"))+state.nearest).forEach{choice ->
                TvFocusButton(onClick={state.select(choice)},colors=ButtonDefaults.buttonColors(
                    containerColor=if(state.selected.code==choice.code)ColorGold else androidx.compose.ui.graphics.Color.DarkGray,
                    contentColor=if(state.selected.code==choice.code)androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.White)){
                    Text(choice.name,fontSize=12.sp,maxLines=1)
                }
            }
        }
        Text("Live TV · ${state.selected.name}",style=MaterialTheme.typography.titleLarge)
        state.locationNote?.let{Text(it,style=MaterialTheme.typography.bodySmall,color=androidx.compose.ui.graphics.Color.LightGray)}
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedTextField(state.query,{state.query=it},modifier=Modifier.weight(1f).widthIn(max=420.dp),singleLine=true,
                label={Text("Find a channel")})
            Text("${visible.size} channels${if(state.saved)" · Saved list" else ""}",fontSize=12.sp)
            TvFocusButton(onClick={state.revision++},enabled=!state.loading){Text("Refresh",fontSize=12.sp)}
        }
        if(state.loading)Text("Loading channels…")
        state.error?.let{Text(it,color=androidx.compose.ui.graphics.Color(0xFFFFAAAA))}
        if(state.guideLoading)Text("Updating program guide…",fontSize=11.sp)
        state.guideError?.let{Text(it,fontSize=11.sp)}
        state.guide?.let{guide ->Text("Guide: ${time(guide.fetched)}${if(guide.saved)" · Saved data" else ""} · epg.pw",fontSize=11.sp,color=androidx.compose.ui.graphics.Color.LightGray)}
        if(!state.loading&&state.error==null&&visible.isEmpty())Text("No channels match.")
        LazyColumn(Modifier.fillMaxWidth().weight(1f),state=state.list,verticalArrangement=Arrangement.spacedBy(6.dp),contentPadding=PaddingValues(bottom=12.dp)){
            items(visible,key={it.id}){channel ->
                val programs=guideByChannel[channel.id].orEmpty()
                val current=programs.filter{it.start<=now&&it.end>now}.maxByOrNull{it.start}
                val next=programs.filter{it.start>now&&it.start>=(current?.end?:now)}.minByOrNull{it.start}
                var focused by remember{mutableStateOf(false)}
                Button(onClick={state.returnChannel=channel.id;onPlay(channel)},
                    modifier=Modifier.fillMaxWidth().heightIn(min=86.dp)
                        .then(if(state.returnChannel==channel.id)Modifier.focusRequester(returnFocus) else Modifier)
                        .onFocusChanged{focused=it.isFocused},
                    border=if(focused)androidx.compose.foundation.BorderStroke(2.dp,androidx.compose.ui.graphics.Color.White) else null,
                    shape=RoundedCornerShape(8.dp),contentPadding=PaddingValues(10.dp),
                    colors=ButtonDefaults.buttonColors(containerColor=androidx.compose.ui.graphics.Color(0xFF253343),contentColor=androidx.compose.ui.graphics.Color.White)){
                    if(channel.logo.isNotBlank())AsyncImage(channel.logo,null,Modifier.size(56.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){
                        Text(channel.name,fontSize=16.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(current?.let{"Now ${time(it.start)}–${time(it.end)} · ${it.title}"}?:"Now · Guide unavailable",fontSize=13.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                        Text(next?.let{"Next ${time(it.start)} · ${it.title}"}?:"Next · Guide unavailable",fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis,color=androidx.compose.ui.graphics.Color.LightGray)
                    }
                    Text("LIVE",color=ColorGold,fontSize=11.sp)
                }
            }
        }
    }
}
private val ColorGold=androidx.compose.ui.graphics.Color(0xFFE5A43B)
private fun time(value:Long):String=DateTimeFormatter.ofPattern("h:mm a",Locale.US).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(value))

@Composable
internal fun JaLiveTvPlayer(channel:JaLiveChannel,onClose:()->Unit) {
    val context=LocalContext.current
    val owner=LocalLifecycleOwner.current
    var source by remember(channel){mutableIntStateOf(0)}
    var retry by remember(channel){mutableIntStateOf(0)}
    var error by remember(channel,source,retry){mutableStateOf<String?>(null)}
    val player=remember(channel,source,retry){ExoPlayer.Builder(context).build().apply{
        setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true)
        setHandleAudioBecomingNoisy(true)
        setMediaItem(MediaItem.fromUri(channel.urls[source]));prepare();playWhenReady=true
    }}
    BackHandler(onBack=onClose)
    DisposableEffect(player,owner){
        JaMusicService.setVideoActive(true)
        var resume=true
        val listener=object:Player.Listener{override fun onPlayerError(failure:PlaybackException){
            if(source+1<channel.urls.size)source++ else error="This channel is unavailable (${failure.errorCodeName}). Try again or choose another channel."
        }}
        val observer=LifecycleEventObserver{_,event->when(event){
            Lifecycle.Event.ON_PAUSE->{resume=player.playWhenReady;player.pause()}
            Lifecycle.Event.ON_RESUME->if(resume)player.play()
            else->Unit
        }}
        player.addListener(listener);owner.lifecycle.addObserver(observer)
        onDispose{owner.lifecycle.removeObserver(observer);player.removeListener(listener);player.release();JaMusicService.setVideoActive(false)}
    }
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)){
        AndroidView(factory={PlayerView(it).apply{
            layoutParams=ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT)
            this.player=player;useController=true;keepScreenOn=true;requestFocus()
        }},update={it.player=player},modifier=Modifier.fillMaxSize(),onReset=null,onRelease={it.player=null;it.keepScreenOn=false})
        error?.let{message->Column(Modifier.align(Alignment.Center).background(androidx.compose.ui.graphics.Color(0xDD202832)).padding(20.dp),horizontalAlignment=Alignment.CenterHorizontally){
            Text(message,color=androidx.compose.ui.graphics.Color.White)
            TvFocusButton(onClick={retry++}){Text("Retry")}
            TvFocusButton(onClick=onClose){Text("Back to channels")}
        }}
    }
}
