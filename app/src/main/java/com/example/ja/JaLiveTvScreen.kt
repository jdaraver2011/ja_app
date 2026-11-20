package com.example.ja

import android.view.ViewGroup
import android.os.SystemClock
import android.util.Log
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.style.TextAlign
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
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Stable
internal class JaLiveTvState(context:android.content.Context,val repository:JaLiveTvRepository,private val scope:CoroutineScope) {
    private val prefs=context.getSharedPreferences("ja_live_favorites",android.content.Context.MODE_PRIVATE)
    var favorites by mutableStateOf(prefs.getStringSet("ids",emptySet())?.toSet()?:emptySet())
    var favoriteOrder by mutableStateOf(runCatching {
        val a=org.json.JSONArray(prefs.getString("order","[]"));(0 until a.length()).map{a.getString(it)}
    }.getOrDefault(emptyList()))
    var reordering by mutableStateOf(false)
    var appliedView:String?=null
    var view by mutableStateOf("United States")
    var channels by mutableStateOf<List<JaLiveChannel>>(emptyList())
    var guide by mutableStateOf<JaLiveGuide?>(null)
    var loading by mutableStateOf(false)
    var guideLoading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var guideError by mutableStateOf<String?>(null)
    var saved by mutableStateOf(false)
    var verifiedAt by mutableStateOf("")
    var query by mutableStateOf("")
    var returnChannel by mutableStateOf<String?>(null)
    private var refreshJob:Job?=null
    private var lastAttempt=0L
    val list=LazyListState()
    val groups=androidx.compose.foundation.ScrollState(0)
    val timeline=androidx.compose.foundation.ScrollState(0)
    private fun saveOrder(){prefs.edit().putStringSet("ids",favorites.toSet()).putString("order",org.json.JSONArray(favoriteOrder).toString()).apply()}
    fun favorite(id:String){
        favorites=if(id in favorites)favorites-id else favorites+id
        favoriteOrder=JaLiveTvOrder.reconcile(favoriteOrder,favorites,channels.map{it.id});saveOrder()
    }
    fun move(id:String,direction:Int){
        // Only visible favorites swap: temporarily missing channels retain their stored position.
        val shown=channels.filter{it.id in favorites&&it.name.contains(query,true)}.map{it.id}.toSet()
        val order=favoriteOrder.filter{it in shown}
        val changed=JaLiveTvOrder.move(order,id,direction)
        val iterator=changed.iterator()
        favoriteOrder=favoriteOrder.map{if(it in shown)iterator.next() else it};saveOrder()
    }
    fun refresh(force:Boolean=false){
        if(refreshJob?.isActive==true)return
        val now=SystemClock.elapsedRealtime()
        if(!force&&channels.isNotEmpty()&&now-lastAttempt<60_000)return
        lastAttempt=now
        // Owned by StreamingApp's scope, so opening a channel does not cancel the EPG download.
        refreshJob=scope.launch {
            loading=true;error=null
            try{
                val result=repository.channels(force)
                channels=result.channels;saved=result.saved;verifiedAt=result.verifiedAt
                favoriteOrder=JaLiveTvOrder.reconcile(favoriteOrder,favorites,channels.map{it.id});saveOrder()
            }catch(e:CancellationException){throw e}catch(_:Exception){error="Channel catalog unavailable. Check your connection and retry."}
            finally{loading=false}
            if(channels.isEmpty())return@launch
            repository.cachedGuide(channels)?.let{guide=it}
            guideLoading=true;guideError=null
            try{guide=repository.guide(channels,force){partial->guide=partial}}
            catch(e:CancellationException){throw e}
            catch(_:Exception){guideError="Guide unavailable. Channels can still be played."}
            finally{guideLoading=false}
        }
    }
}

private val ColorGold=Color(0xFFE5A43B)
private val liveTimeFormat=DateTimeFormatter.ofPattern("h:mm a",Locale.US)
private fun time(value:Long):String=liveTimeFormat.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(value))

@Composable
internal fun JaLiveTvScreen(state:JaLiveTvState,onPlay:(JaLiveChannel)->Unit,modifier:Modifier=Modifier) {
    val owner=LocalLifecycleOwner.current
    var resumed by remember{mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))}
    DisposableEffect(owner){val observer=LifecycleEventObserver{_,_->resumed=owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)}
        owner.lifecycle.addObserver(observer);onDispose{owner.lifecycle.removeObserver(observer)}}
    LaunchedEffect(resumed){if(resumed)while(isActive){state.refresh();delay(900_000)}}
    var now by remember{mutableLongStateOf(System.currentTimeMillis())}
    LaunchedEffect(resumed){if(resumed)while(isActive){now=System.currentTimeMillis();delay(30_000)}}
    val programs=state.guide?.byChannel.orEmpty()
    val start=now/1_800_000*1_800_000
    val finish=start+86_400_000
    val visible=remember(state.channels,state.query,state.view,state.favorites,state.favoriteOrder,programs,now){state.channels.filter{channel ->
        JaLiveTvRules.categoryMatches(state.view,channel.categories)
            &&(state.view!="Favorites"||channel.id in state.favorites)
            &&(state.view!="With EPG"||programs[channel.id].orEmpty().any{JaLiveTvRules.hasGuide(it.start,it.end,it.title,now,finish)})
            &&channel.name.contains(state.query,true)}.let{matches->
            if(state.view=="Favorites"){
                val ranks=state.favoriteOrder.withIndex().associate{it.value to it.index}
                matches.sortedBy{ranks[it.id]?:Int.MAX_VALUE}
            }else matches
        }}
    LaunchedEffect(state.view){
        if(state.appliedView!=state.view){
            state.reordering=false
            if(state.returnChannel==null)state.list.scrollToItem(0)
            state.appliedView=state.view
        }
    }
    val returnFocus=remember{FocusRequester()}
    LaunchedEffect(state.returnChannel,visible.map{it.id}){
        val id=state.returnChannel?:return@LaunchedEffect
        val index=visible.indexOfFirst{it.id==id}
        if(index>=0){
            if(state.list.layoutInfo.visibleItemsInfo.none{it.key==id})state.list.scrollToItem(index)
            withFrameNanos{};withFrameNanos{}
            if(runCatching{returnFocus.requestFocus();true}.getOrDefault(false))state.returnChannel=null
        }
    }
    val scroll=state.timeline
    val minuteWidth=2.dp
    val totalWidth=minuteWidth*1440
    val density=androidx.compose.ui.platform.LocalDensity.current
    val minutePixels=with(density){minuteWidth.toPx()}
    val firstMinute by remember(scroll,minutePixels){derivedStateOf{((scroll.value/minutePixels)/15).toInt()*15}}
    BoxWithConstraints(modifier){
        val channelWidth=(if(maxWidth>=600.dp)180.dp else 140.dp)+(if(state.view=="Favorites"&&state.reordering)64.dp else 0.dp)
        Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(6.dp)){
            Row(Modifier.horizontalScroll(state.groups),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                JaLiveTvRules.views().forEach{label->
                    TvFocusButton(onClick={state.view=label},colors=ButtonDefaults.buttonColors(
                        containerColor=if(state.view==label)ColorGold else Color.DarkGray,
                        contentColor=if(state.view==label)Color.Black else Color.White)){Text(label,fontSize=12.sp)}
                }
            }
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                Text("IPTV",style=MaterialTheme.typography.titleLarge,color=Color.White)
                OutlinedTextField(state.query,{state.query=it},modifier=Modifier.weight(1f),singleLine=true,
                    label={Text("Find a channel")},textStyle=LocalTextStyle.current.copy(color=Color.White))
                TvFocusButton(onClick={state.refresh(true)},enabled=!state.loading&&!state.guideLoading){Text("Refresh",fontSize=12.sp)}
            }
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                Text("${visible.size} channels",fontSize=12.sp,color=Color.White)
                if(state.view=="Favorites")TvFocusButton(onClick={state.reordering=!state.reordering},enabled=state.reordering||visible.isNotEmpty()){
                    Text(if(state.reordering)"Done" else "Reorder",fontSize=12.sp)
                }
                if(state.loading||state.guideLoading)Text(if(state.loading)"Updating channels…" else "Updating guide…",fontSize=11.sp,color=Color.LightGray)
            }
            state.error?.let{Text(it,color=Color(0xFFFFAAAA),fontSize=12.sp)}
            state.guideError?.let{Text(it,fontSize=11.sp,color=Color.LightGray)}
            if(!state.loading&&visible.isEmpty())Text(when {
                state.view=="With EPG"&&state.guideLoading->"Loading channels with program schedules…"
                state.view=="With EPG"->"No matching channels have current or upcoming guide data."
                state.view=="Favorites"&&state.favorites.isEmpty()->"Use the star beside a channel to add it to Favorites."
                else->"No matching live channels."
            })
            Row(Modifier.fillMaxWidth().height(28.dp).background(Color(0xFF314254)),verticalAlignment=Alignment.CenterVertically){
                Text("Channel",Modifier.width(channelWidth).padding(start=38.dp),fontSize=12.sp,color=Color.White)
                Row(Modifier.weight(1f).horizontalScroll(scroll)){
                    repeat(48){slot->Text(time(start+slot*1_800_000),Modifier.width(minuteWidth*30).padding(start=4.dp),fontSize=11.sp,maxLines=1)}
                }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f),state=state.list,verticalArrangement=Arrangement.spacedBy(4.dp),contentPadding=PaddingValues(bottom=12.dp)){
                items(visible,key={it.id}){channel ->
                    val visibleStart=start+(firstMinute-30).coerceAtLeast(0)*60_000L
                    val visibleEnd=(start+(firstMinute+((maxWidth-channelWidth).value/2).toInt()+30)*60_000L).coerceAtMost(finish)
                    val channelPrograms=programs[channel.id].orEmpty()
                    val allEntries=remember(channelPrograms,start,finish){channelPrograms.filter{it.end>start&&it.start<finish}}
                    val entries=remember(allEntries,visibleStart,visibleEnd){
                        val first=allEntries.indexOfFirst{it.end>visibleStart}
                        val last=allEntries.indexOfLast{it.start<visibleEnd}
                        if(first<0||last<first)emptyList() else allEntries.subList((first-1).coerceAtLeast(0),(last+2).coerceAtMost(allEntries.size))
                    }
                    Row(Modifier.fillMaxWidth().height(84.dp),verticalAlignment=Alignment.CenterVertically){
                        Row(Modifier.width(channelWidth).fillMaxHeight(),verticalAlignment=Alignment.CenterVertically){
                            var starFocus by remember{mutableStateOf(false)}
                            IconButton(onClick={state.favorite(channel.id)},modifier=Modifier.width(32.dp).height(44.dp)
                                .semantics{contentDescription=if(channel.id in state.favorites)"Remove ${channel.name} from favorites" else "Add ${channel.name} to favorites"}
                                .onFocusChanged{starFocus=it.isFocused}.border(if(starFocus)2.dp else 0.dp,if(starFocus)Color.White else Color.Transparent,RoundedCornerShape(6.dp))){
                                Text(if(channel.id in state.favorites)"★" else "☆",color=ColorGold,fontSize=22.sp)
                            }
                            if(state.view=="Favorites"&&state.reordering){
                                val index=visible.indexOfFirst{it.id==channel.id}
                                JaLiveMoveButton("↑","Move ${channel.name} up",index>0){state.move(channel.id,-1)}
                                JaLiveMoveButton("↓","Move ${channel.name} down",index<visible.lastIndex){state.move(channel.id,1)}
                            }
                            var focused by remember{mutableStateOf(false)}
                            Button(onClick={state.returnChannel=channel.id;onPlay(channel)},
                                modifier=Modifier.weight(1f).fillMaxHeight().then(if(state.returnChannel==channel.id)Modifier.focusRequester(returnFocus) else Modifier)
                                    .onFocusChanged{focused=it.isFocused},
                                border=if(focused)androidx.compose.foundation.BorderStroke(2.dp,Color.White) else null,
                                shape=RoundedCornerShape(6.dp),contentPadding=PaddingValues(4.dp),
                                colors=ButtonDefaults.buttonColors(containerColor=Color(0xFF253343),contentColor=Color.White)){
                                Column(horizontalAlignment=Alignment.CenterHorizontally){
                                    if(channel.logo.isNotBlank())AsyncImage(channel.logo,null,Modifier.size(28.dp))
                                    Text(channel.name,fontSize=11.sp,maxLines=2,overflow=TextOverflow.Ellipsis,textAlign=TextAlign.Center)
                                }
                            }

                        }
                        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds().horizontalScroll(scroll)){
                            Box(Modifier.width(totalWidth).fillMaxHeight().background(Color(0xFF1D2938))){
                                if(allEntries.isEmpty())Box(Modifier.width(240.dp).fillMaxHeight().clickable{state.returnChannel=channel.id;onPlay(channel)},contentAlignment=Alignment.CenterStart){
                                    Text("Guide unavailable · Watch live",Modifier.padding(8.dp),color=Color.LightGray,fontSize=12.sp)
                                }
                                entries.forEach{program->key(program.channel,program.start,program.end,program.title){
                                    val left=minuteWidth*((program.start.coerceAtLeast(start)-start)/60000f)
                                    val width=minuteWidth*((program.end.coerceAtMost(finish)-program.start.coerceAtLeast(start))/60000f)
                                    var programFocus by remember(program){mutableStateOf(false)}
                                    Button(onClick={state.returnChannel=channel.id;onPlay(channel)},
                                        modifier=Modifier.offset(x=left).width(width.coerceAtLeast(8.dp)).fillMaxHeight().padding(end=2.dp)
                                            .onFocusChanged{programFocus=it.isFocused},
                                        border=if(programFocus)androidx.compose.foundation.BorderStroke(2.dp,Color.White) else null,
                                        shape=RoundedCornerShape(4.dp),contentPadding=PaddingValues(6.dp),
                                        colors=ButtonDefaults.buttonColors(containerColor=if(program.start<=now&&program.end>now)Color(0xFF38516B) else Color(0xFF283747),contentColor=Color.White)){
                                        Column(Modifier.fillMaxWidth()){
                                            Text(program.title,fontSize=12.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                                            Text("${time(program.start)}–${time(program.end)}",fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                                        }
                                    }
                                }}
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable
private fun JaLiveMoveButton(symbol:String,label:String,enabled:Boolean,onClick:()->Unit){
    var focused by remember{mutableStateOf(false)}
    IconButton(onClick=onClick,enabled=enabled,modifier=Modifier.width(32.dp).height(44.dp)
        .semantics{contentDescription=label}.onFocusChanged{focused=it.isFocused}
        .border(if(focused)2.dp else 0.dp,if(focused)Color.White else Color.Transparent,RoundedCornerShape(6.dp))){
        Text(symbol,color=if(enabled)Color.White else Color.Gray,fontSize=20.sp)
    }
}

/** Resolve current alternatives before creating a player; no Movie/TV source changes. */
@Composable
internal fun JaLiveTvPlayer(channel:JaLiveChannel,repository:JaLiveTvRepository,onClose:()->Unit) {
    var revision by remember(channel){mutableIntStateOf(0)}
    var resolved by remember(channel){mutableStateOf<JaLiveChannel?>(null)}
    var resolveError by remember(channel){mutableStateOf(false)}
    BackHandler(onBack=onClose)
    LaunchedEffect(channel,revision){
        resolved=null;resolveError=false
        resolved=try{repository.playbackChannel(channel,revision>0)}catch(e:CancellationException){throw e}catch(_:Exception){resolveError=true;null}
        Log.i("JA_LIVE_TV","SOURCES_READY channel=${channel.id} count=${resolved?.urls?.size}")
    }
    val ready=resolved
    if(ready==null)Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black),contentAlignment=Alignment.Center){
        if(resolveError)Column(horizontalAlignment=Alignment.CenterHorizontally){
            Text("This live channel is currently unavailable.",color=Color.White)
            TvFocusButton(onClick={revision++}){Text("Refresh and retry")}
            TvFocusButton(onClick=onClose){Text("Back to channels")}
        }else Text("Connecting to ${channel.name}…",color=Color.White)
    }else key(revision){JaLiveTvSession(ready,onRetry={revision++},onClose=onClose)}
}

private data class JaLiveReconnect(val target:Int,val reason:String,val delayMs:Long)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun JaLiveTvSession(channel:JaLiveChannel,onRetry:()->Unit,onClose:()->Unit) {
    val context=LocalContext.current
    val owner=LocalLifecycleOwner.current
    var resumed by remember{mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))}
    var wantsPlayback by remember(channel){mutableStateOf(true)}
    var source by remember(channel){mutableIntStateOf(0)}
    var generation by remember(channel){mutableIntStateOf(0)}
    val failures=remember(channel){IntArray(channel.urls.size)}
    val ended=remember(channel){BooleanArray(channel.urls.size)}
    var recovery by remember(channel){mutableStateOf<JaLiveReconnect?>(null)}
    var error by remember(channel){mutableStateOf<String?>(null)}
    val player=remember(channel,source,generation){
        val headers=channel.headers[channel.urls[source]].orEmpty()
        val http=DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
            .setUserAgent(headers["User-Agent"]?:"JA/2.4.6").setDefaultRequestProperties(headers)
        ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context,http))).build().apply{
        setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true)
        setHandleAudioBecomingNoisy(true)
        setMediaItem(MediaItem.fromUri(channel.urls[source]));prepare();playWhenReady=wantsPlayback&&resumed
    }}
    fun reconnect(reason:String,finished:Boolean=false) {
        if(recovery!=null||error!=null)return
        failures[source]++
        if(finished)ended[source]=true
        val target=JaLiveTvRecovery.next(source,failures,ended)
        Log.w("JA_LIVE_TV","RECOVERY channel=${channel.id} source=$source reason=$reason next=$target")
        if(target<0){
            error=if(ended.all{it})"No ongoing live feed is available for this channel. Its listed feeds have ended."
                else "The channel could not reconnect. Try again or choose another channel."
        }else recovery=JaLiveReconnect(target,reason,JaLiveTvRecovery.backoff(failures[source]))
        // stop() preserves playWhenReady. Never repeat a finite clip to simulate live TV.
        player.stop()
    }
    LaunchedEffect(player,recovery,resumed,wantsPlayback){
        val pending=recovery?:return@LaunchedEffect
        if(!resumed||!wantsPlayback)return@LaunchedEffect
        delay(pending.delayMs)
        recovery=null;error=null;source=pending.target;generation++
    }
    DisposableEffect(player,owner){
        JaMusicService.setVideoActive(true)
        var internalChange=false
        val listener=object:Player.Listener{
            override fun onPlayerError(failure:PlaybackException){
                reconnect(if(failure.errorCode==PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW)"BEHIND_LIVE_WINDOW" else failure.errorCodeName)
            }
            override fun onTimelineChanged(timeline:Timeline,reason:Int){
                val manifest=player.currentManifest as? HlsManifest
                if(manifest?.mediaPlaylist?.hasEndTag==true)reconnect("HLS_ENDLIST",true)
                else if(!timeline.isEmpty)Log.i("JA_LIVE_TV","TIMELINE channel=${channel.id} source=$source live=${player.isCurrentMediaItemLive} dynamic=${player.isCurrentMediaItemDynamic}")
            }
            override fun onPlaybackStateChanged(state:Int){
                if(state==Player.STATE_ENDED&&wantsPlayback)reconnect("PLAYBACK_ENDED",true)
            }
            override fun onPlayWhenReadyChanged(playWhenReady:Boolean,reason:Int){
                if(!internalChange&&(reason==Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
                    ||reason==Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY))wantsPlayback=playWhenReady
            }
        }
        val observer=LifecycleEventObserver{_,event->
            internalChange=true
            when(event){
                Lifecycle.Event.ON_PAUSE->{resumed=false;player.pause()}
                Lifecycle.Event.ON_RESUME->{resumed=true;if(wantsPlayback&&recovery==null&&error==null)player.play()}
                else->Unit
            }
            internalChange=false
        }
        player.addListener(listener);owner.lifecycle.addObserver(observer)
        Log.i("JA_LIVE_TV","OPEN channel=${channel.id} source=$source generation=$generation")
        onDispose{owner.lifecycle.removeObserver(observer);player.removeListener(listener);player.release();JaMusicService.setVideoActive(false)}
    }
    LaunchedEffect(player){
        var lastPosition=player.currentPosition
        var lastProgress=SystemClock.elapsedRealtime()
        var healthySince=0L
        while(isActive){
            delay(1000)
            val now=SystemClock.elapsedRealtime()
            val position=player.currentPosition
            if(position!=lastPosition||!resumed||!wantsPlayback||player.playbackSuppressionReason!=Player.PLAYBACK_SUPPRESSION_REASON_NONE){
                lastProgress=now;lastPosition=position
            }
            if(player.isPlaying){
                if(healthySince==0L)healthySince=now
                if(now-healthySince>=60000){failures[source]=0;healthySince=now}
            }else healthySince=0L
            if(player.playbackState in listOf(Player.STATE_READY,Player.STATE_BUFFERING)
                &&JaLiveTvRecovery.stalled(resumed,wantsPlayback,
                    player.playbackSuppressionReason!=Player.PLAYBACK_SUPPRESSION_REASON_NONE,recovery!=null,lastProgress,now)){
                reconnect("NO_PROGRESS_30S")
            }
        }
    }
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)){
        AndroidView(factory={PlayerView(it).apply{
            layoutParams=ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT)
            this.player=player;useController=true;keepScreenOn=true;requestFocus()
        }},update={it.player=player},modifier=Modifier.fillMaxSize(),onReset=null,onRelease={it.player=null;it.keepScreenOn=false})
        if(recovery!=null)Text("Reconnecting live stream…",color=androidx.compose.ui.graphics.Color.White,
            modifier=Modifier.align(Alignment.TopCenter).background(androidx.compose.ui.graphics.Color(0xCC202832)).padding(12.dp))
        error?.let{message->Column(Modifier.align(Alignment.Center).background(androidx.compose.ui.graphics.Color(0xDD202832)).padding(20.dp),horizontalAlignment=Alignment.CenterHorizontally){
            Text(message,color=androidx.compose.ui.graphics.Color.White)
            TvFocusButton(onClick=onRetry){Text("Refresh sources and retry")}
            TvFocusButton(onClick=onClose){Text("Back to channels")}
        }}
    }
}
