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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.onPreviewKeyEvent
import android.view.KeyEvent
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
    private val moveSession=JaLiveTvMoveSession()
    private val groupOrders=mutableStateMapOf<String,List<String>>()
    var movingId by mutableStateOf<String?>(null)
    var draftOrder by mutableStateOf<List<String>>(emptyList())
    private var movingGroup=""
    var suppressPickUpRelease=false
    var sidebarOpen by mutableStateOf(true)
    var focusedChannel:String?=null
    val mainFocus=FocusRequester()
    val groupFocus=FocusRequester()
    val groupList=LazyListState()
    var previewChannel by mutableStateOf<JaLiveChannel?>(null)
    var playerAttached by mutableStateOf(false)
    var fullscreen by mutableStateOf(false)
    var lastChannelId=prefs.getString("last-channel",null)
    init {
        JaLiveTvRules.views().forEach{group->
            val stored=runCatching { val a=org.json.JSONArray(prefs.getString("group-order:$group","[]"));(0 until a.length()).map{a.getString(it)} }.getOrDefault(emptyList())
            groupOrders[group]=if(group=="Favorites"&&stored.isEmpty())favoriteOrder else stored
        }
    }
    fun order(group:String,available:List<String>):List<String> = JaLiveTvMoveSession.displayed(
        if(movingId!=null&&movingGroup==group)draftOrder else groupOrders[group].orEmpty(),available)
    fun beginMove(id:String,visible:List<String>,remote:Boolean){
        if(movingId!=null)return
        moveSession.begin(visible,id)
        movingId=moveSession.channel();draftOrder=moveSession.order();movingGroup=view;suppressPickUpRelease=remote
        returnChannel=id
    }
    fun moveChannel(direction:Int){
        moveSession.move(direction);draftOrder=moveSession.order();returnChannel=movingId
    }
    fun finishMove(save:Boolean){
        if(movingId==null)return
        val id=movingId
        if(save){
            val available=channels.filter{JaLiveTvRules.categoryMatches(movingGroup,it.categories)&&(movingGroup!="Favorites"||it.id in favorites)}.map{it.id}
            val merged=JaLiveTvMoveSession.merge(groupOrders[movingGroup].orEmpty(),available,draftOrder)
            groupOrders[movingGroup]=merged
            val edit=prefs.edit().putString("group-order:$movingGroup",org.json.JSONArray(merged).toString())
            if(movingGroup=="Favorites"){favoriteOrder=merged;edit.putString("order",org.json.JSONArray(merged).toString())}
            edit.apply()
        }
        moveSession.clear();movingId=null;draftOrder=emptyList();suppressPickUpRelease=false;returnChannel=id
    }
    fun playbackStarted(channel:JaLiveChannel){
        if(lastChannelId!=channel.id){lastChannelId=channel.id;prefs.edit().putString("last-channel",channel.id).apply()}
    }
    fun tune(channel:JaLiveChannel,full:Boolean){
        finishMove(false);previewChannel=channel;fullscreen=full;focusedChannel=channel.id
    }
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
        favoriteOrder=JaLiveTvOrder.reconcile(favoriteOrder,favorites,channels.map{it.id})
        groupOrders["Favorites"]=favoriteOrder
        prefs.edit().putString("group-order:Favorites",org.json.JSONArray(favoriteOrder).toString()).apply()
        saveOrder()
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
                favoriteOrder=JaLiveTvOrder.reconcile(favoriteOrder,favorites,channels.map{it.id})
        groupOrders["Favorites"]=favoriteOrder
        prefs.edit().putString("group-order:Favorites",org.json.JSONArray(favoriteOrder).toString()).apply()
        saveOrder()
            }catch(e:CancellationException){throw e}catch(_:Exception){error="Channel catalog unavailable. Check your connection and retry."}
            finally{loading=false}
            if(channels.isEmpty())return@launch
            guideLoading=true;guideError=null
            repository.cachedGuide(channels)?.let{guide=it}
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
internal fun JaLiveTvScreen(state:JaLiveTvState,onMainMenu:()->Unit,modifier:Modifier=Modifier) {
    val scope=rememberCoroutineScope()
    val owner=LocalLifecycleOwner.current
    var resumed by remember{mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))}
    DisposableEffect(owner){val observer=LifecycleEventObserver{_,_->resumed=owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)}
        owner.lifecycle.addObserver(observer);onDispose{owner.lifecycle.removeObserver(observer)}}
    LaunchedEffect(resumed){if(resumed)while(isActive){state.refresh();delay(900_000)}}
    LaunchedEffect(state.channels,state.fullscreen){
        if(!state.fullscreen&&state.channels.isNotEmpty()&&(state.previewChannel==null||state.channels.none{it.id==state.previewChannel?.id})){
            val target=state.channels.firstOrNull{it.id==state.lastChannelId}
                ?:state.favoriteOrder.firstNotNullOfOrNull{id->state.channels.firstOrNull{it.id==id}}
                ?:state.channels.first()
            state.tune(target,false)
        }
    }
    var now by remember{mutableLongStateOf(System.currentTimeMillis())}
    LaunchedEffect(resumed){if(resumed)while(isActive){now=System.currentTimeMillis();delay(30_000)}}
    val programs=state.guide?.byChannel.orEmpty()
    val start=now/1_800_000*1_800_000
    val finish=start+86_400_000
    val available=remember(state.channels,state.query,state.view,state.favorites,programs,now){state.channels.filter{channel->
        JaLiveTvRules.categoryMatches(state.view,channel.categories)
            &&(state.view!="Favorites"||channel.id in state.favorites)
            &&(state.view!="With EPG"||programs[channel.id].orEmpty().any{JaLiveTvRules.hasGuide(it.start,it.end,it.title,now,finish)})
            &&channel.name.contains(state.query,true)}}
    val channelMap=remember(available){available.associateBy{it.id}}
    val visibleIds=state.order(state.view,available.map{it.id})
    val visible=remember(channelMap,visibleIds){visibleIds.mapNotNull{channelMap[it]}}
    val returnFocus=remember{FocusRequester()}
    fun focusGuide(){
        state.sidebarOpen=false
        state.returnChannel=state.focusedChannel?.takeIf{it in channelMap}?:visible.firstOrNull()?.id
    }
    fun focusGroups(){
        state.sidebarOpen=true
        scope.launch{
            val index=JaLiveTvRules.views().indexOf(state.view).coerceAtLeast(0)
            state.groupList.scrollToItem(index);withFrameNanos{};withFrameNanos{}
            runCatching{state.groupFocus.requestFocus()}
        }
    }
    fun selectGroup(group:String){
        if(state.view!=group){state.finishMove(false);state.view=group;state.returnChannel=null}
    }
    fun play(channel:JaLiveChannel){state.tune(channel,true)}
    LaunchedEffect(state.view){
        if(state.appliedView!=state.view){
            if(state.returnChannel==null)state.list.scrollToItem(0)
            state.appliedView=state.view
        }
    }
    LaunchedEffect(state.movingId,visibleIds){if(state.movingId!=null&&state.movingId !in channelMap)state.finishMove(false)}
    LaunchedEffect(state.returnChannel,visibleIds){
        val id=state.returnChannel?:return@LaunchedEffect
        val index=visible.indexOfFirst{it.id==id}
        if(index>=0){
            if(state.list.layoutInfo.visibleItemsInfo.none{it.key==id})state.list.scrollToItem(index)
            withFrameNanos{};withFrameNanos{}
            if(runCatching{returnFocus.requestFocus();true}.getOrDefault(false))state.returnChannel=null
        }
    }
    BackHandler(state.sidebarOpen&&!state.fullscreen&&state.movingId==null){focusGuide()}
    BackHandler(state.movingId!=null){state.finishMove(false)}
    val scroll=state.timeline
    val minuteWidth=2.dp
    val totalWidth=minuteWidth*1440
    val density=androidx.compose.ui.platform.LocalDensity.current
    val minutePixels=with(density){minuteWidth.toPx()}
    val firstMinute by remember(scroll,minutePixels){derivedStateOf{((scroll.value/minutePixels)/15).toInt()*15}}
    BoxWithConstraints(modifier.onPreviewKeyEvent{event->
        val key=event.nativeKeyEvent
        if(state.movingId==null)false else when(key.keyCode){
            KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN->{
                if(key.action==KeyEvent.ACTION_DOWN)state.moveChannel(if(key.keyCode==KeyEvent.KEYCODE_DPAD_UP)-1 else 1)
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_NUMPAD_ENTER->{
                if(key.action==KeyEvent.ACTION_UP){
                    if(state.suppressPickUpRelease)state.suppressPickUpRelease=false else state.finishMove(true)
                };true
            }
            KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT->true
            else->false
        }
    }){
        val sidebarWidth=if(state.sidebarOpen)minOf(180.dp,maxWidth*0.32f) else 0.dp
        val guideWidth=(maxWidth-sidebarWidth-8.dp).coerceAtLeast(80.dp)
        val channelWidth=if(guideWidth>=600.dp)180.dp else 140.dp
        val viewportMinutes=((guideWidth-channelWidth).value/2).toInt().coerceAtLeast(1)
        val previewWidth=minOf(300.dp,guideWidth*0.52f,maxHeight*0.28f*(16f/9))
        val previewHeight=previewWidth*(9f/16)
        Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                TvFocusButton(onClick={if(state.sidebarOpen)focusGuide() else focusGroups()}){Text("Groups",fontSize=12.sp)}
                Text("IPTV",style=MaterialTheme.typography.titleLarge,color=Color.White)
                JaEntryField(state.query,{state.finishMove(false);state.returnChannel=null;state.query=it},modifier=Modifier.weight(1f),label={Text("Find a channel")})
                TvFocusButton(onClick={state.refresh(true)},enabled=!state.loading&&!state.guideLoading){Text("Refresh",fontSize=12.sp)}
            }
            Row(Modifier.fillMaxWidth().weight(1f),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                if(state.sidebarOpen)Column(Modifier.width(sidebarWidth).fillMaxHeight().background(Color(0xFF1B2735),RoundedCornerShape(6.dp))){
                    Text("Groups",Modifier.padding(10.dp),color=ColorGold,fontSize=16.sp)
                    LazyColumn(state=state.groupList,modifier=Modifier.fillMaxWidth().weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp),contentPadding=PaddingValues(6.dp)){
                        items(JaLiveTvRules.views(),key={it}){group->
                            TvFocusButton(onClick={selectGroup(group);focusGuide()},modifier=Modifier.fillMaxWidth()
                                .then(if(state.view==group)Modifier.focusRequester(state.groupFocus) else Modifier)
                                .onFocusChanged{if(it.isFocused)selectGroup(group)}
                                .onPreviewKeyEvent{event->val key=event.nativeKeyEvent
                                    if(key.keyCode==KeyEvent.KEYCODE_DPAD_RIGHT){if(key.action==KeyEvent.ACTION_DOWN)focusGuide();true}
                                    else if(key.keyCode==KeyEvent.KEYCODE_DPAD_LEFT){if(key.action==KeyEvent.ACTION_DOWN)onMainMenu();true}else false},
                                colors=ButtonDefaults.buttonColors(containerColor=if(state.view==group)ColorGold else Color(0xFF263747),
                                    contentColor=if(state.view==group)Color.Black else Color.White)){
                                Text(group,fontSize=12.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight(),verticalArrangement=Arrangement.spacedBy(6.dp)){
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                        Box(Modifier.width(previewWidth).height(previewHeight).background(Color.Black,RoundedCornerShape(6.dp)).clipToBounds()){
                            val channel=state.previewChannel
                            if(channel==null)Text("Select a channel",Modifier.align(Alignment.Center),color=Color.LightGray,fontSize=12.sp)
                            else key(channel.id){JaLiveTvPlayback(channel,state.repository,state.fullscreen,
                                onFullscreen={state.fullscreen=it;if(!it)state.returnChannel=channel.id.takeIf{id->id in channelMap}?:visible.firstOrNull()?.id},
                                onPlaying={state.playbackStarted(it)},onAttached={state.playerAttached=it})}
                        }
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){
                            Text(state.previewChannel?.name?:"IPTV",color=Color.White,fontSize=16.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                            val current=programs[state.previewChannel?.id].orEmpty().firstOrNull{it.start<=now&&it.end>now}
                            Text(current?.title?:"Live television",color=Color.LightGray,fontSize=12.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                            current?.let{Text("${time(it.start)}–${time(it.end)}",color=Color.LightGray,fontSize=11.sp)}
                            Text("${state.view} · ${visible.size} channels",color=ColorGold,fontSize=11.sp)
                            if(state.movingId!=null)Text("Moving: ${channelMap[state.movingId]?.name.orEmpty()}",color=ColorGold,fontSize=12.sp,maxLines=2)
                            else if(state.loading||state.guideLoading)Text(if(state.loading)"Updating channels…" else "Updating guide…",fontSize=11.sp,color=Color.LightGray)
                        }
                    }
                    state.error?.let{Text(it,color=Color(0xFFFFAAAA),fontSize=11.sp)}
                    state.guideError?.let{Text(it,color=Color.LightGray,fontSize=11.sp)}
                    if(visible.isEmpty())Text(if(state.guideLoading&&state.view=="With EPG")"Loading program schedules…" else "No matching channels",color=Color.LightGray,fontSize=12.sp)
                    Row(Modifier.fillMaxWidth().height(28.dp).background(Color(0xFF314254)),verticalAlignment=Alignment.CenterVertically){
                        Text("Channel",Modifier.width(channelWidth).padding(start=38.dp),fontSize=12.sp,color=Color.White)
                        Row(Modifier.weight(1f).horizontalScroll(scroll)){
                            repeat(48){slot->Text(time(start+slot*1_800_000),Modifier.width(minuteWidth*30).padding(start=4.dp),fontSize=11.sp,maxLines=1,color=Color.White)}
                        }
                    }
                    LazyColumn(Modifier.fillMaxWidth().weight(1f),state=state.list,verticalArrangement=Arrangement.spacedBy(4.dp),contentPadding=PaddingValues(bottom=12.dp)){
                        items(visible,key={it.id}){channel->
                            val visibleStart=start+(firstMinute-30).coerceAtLeast(0)*60_000L
                            val visibleEnd=(start+(firstMinute+viewportMinutes+30)*60_000L).coerceAtMost(finish)
                            val channelPrograms=programs[channel.id].orEmpty()
                            val allEntries=remember(channelPrograms,start,finish){channelPrograms.filter{it.end>start&&it.start<finish}}
                            val entries=remember(allEntries,visibleStart,visibleEnd){
                                val first=allEntries.indexOfFirst{it.end>visibleStart};val last=allEntries.indexOfLast{it.start<visibleEnd}
                                if(first<0||last<first)emptyList() else allEntries.subList((first-1).coerceAtLeast(0),(last+2).coerceAtMost(allEntries.size))
                            }
                            Row(Modifier.fillMaxWidth().height(78.dp),verticalAlignment=Alignment.CenterVertically){
                                Row(Modifier.width(channelWidth).fillMaxHeight(),verticalAlignment=Alignment.CenterVertically){
                                    var starFocused by remember{mutableStateOf(false)}
                                    IconButton(onClick={state.finishMove(false);state.favorite(channel.id)},modifier=Modifier.width(32.dp).height(44.dp)
                                        .onPreviewKeyEvent{event->if(event.nativeKeyEvent.keyCode==KeyEvent.KEYCODE_DPAD_LEFT){if(event.nativeKeyEvent.action==KeyEvent.ACTION_DOWN)focusGroups();true}else false}
                                        .semantics{contentDescription=if(channel.id in state.favorites)"Remove ${channel.name} from favorites" else "Add ${channel.name} to favorites"}
                                        .onFocusChanged{starFocused=it.isFocused}.border(if(starFocused)2.dp else 0.dp,if(starFocused)Color.White else Color.Transparent,RoundedCornerShape(6.dp))){
                                        Text(if(channel.id in state.favorites)"★" else "☆",color=ColorGold,fontSize=22.sp)
                                    }
                                    JaLiveChannelButton(channel,moving=state.movingId==channel.id,
                                        modifier=Modifier.weight(1f).fillMaxHeight().then(if(state.returnChannel==channel.id)Modifier.focusRequester(returnFocus) else Modifier),
                                        onFocus={state.focusedChannel=channel.id},onPlay={play(channel)},
                                        onPickUp={remote->state.beginMove(channel.id,visibleIds,remote)},
                                        onDragMove={state.moveChannel(it)},onDrop={state.finishMove(true)},onCancel={state.finishMove(false)})
                                }
                                Box(Modifier.weight(1f).fillMaxHeight().clipToBounds().horizontalScroll(scroll)){
                                    Box(Modifier.width(totalWidth).fillMaxHeight().background(Color(0xFF1D2938))){
                                        if(allEntries.isEmpty())Box(Modifier.width(240.dp).fillMaxHeight().clickable{play(channel)},contentAlignment=Alignment.CenterStart){
                                            Text("Guide unavailable · Watch live",Modifier.padding(8.dp),color=Color.LightGray,fontSize=12.sp)
                                        }
                                        entries.forEach{program->key(program.channel,program.start,program.end,program.title){
                                            val left=minuteWidth*((program.start.coerceAtLeast(start)-start)/60000f)
                                            val width=minuteWidth*((program.end.coerceAtMost(finish)-program.start.coerceAtLeast(start))/60000f)
                                            var focused by remember(program){mutableStateOf(false)}
                                            Button(onClick={play(channel)},modifier=Modifier.offset(x=left).width(width.coerceAtLeast(8.dp)).fillMaxHeight().padding(end=2.dp)
                                                .onFocusChanged{focused=it.isFocused},border=if(focused)androidx.compose.foundation.BorderStroke(2.dp,Color.White) else null,
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
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun JaLiveChannelButton(channel:JaLiveChannel,moving:Boolean,modifier:Modifier,onFocus:()->Unit,
    onPlay:()->Unit,onPickUp:(Boolean)->Unit,onDragMove:(Int)->Unit,onDrop:()->Unit,onCancel:()->Unit){
    var focused by remember{mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    var hold by remember{mutableStateOf<Job?>(null)}
    var longPress by remember{mutableStateOf(false)}
    val pickUp by rememberUpdatedState(onPickUp)
    val play by rememberUpdatedState(onPlay)
    val move by rememberUpdatedState(onDragMove)
    val drop by rememberUpdatedState(onDrop)
    val cancel by rememberUpdatedState(onCancel)
    val threshold=with(androidx.compose.ui.platform.LocalDensity.current){78.dp.toPx()}
    DisposableEffect(Unit){onDispose{hold?.cancel()}}
    Box(modifier.onFocusChanged{focused=it.isFocused;if(it.isFocused)onFocus() else hold?.cancel()}
        .onPreviewKeyEvent{event->val key=event.nativeKeyEvent
            if(key.keyCode !in listOf(KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_NUMPAD_ENTER))false
            else{
                if(key.action==KeyEvent.ACTION_DOWN&&key.repeatCount==0){
                    longPress=false;hold?.cancel();hold=scope.launch{delay(android.view.ViewConfiguration.getLongPressTimeout().toLong());longPress=true;pickUp(true)}
                }else if(key.action==KeyEvent.ACTION_UP){hold?.cancel();if(!longPress&&!key.isCanceled&&focused)play()}
                true
            }
        }
        .pointerInput(channel.id){var distance=0f
            detectDragGesturesAfterLongPress(onDragStart={distance=0f;pickUp(false)},onDragEnd={drop()},onDragCancel={cancel()}){change,delta->
                change.consume();distance+=delta.y
                while(kotlin.math.abs(distance)>=threshold){val direction=if(distance>0)1 else -1;move(direction);distance-=threshold*direction}
            }
        }
        .combinedClickable(onClick=onPlay,onLongClick={onPickUp(false)})
        .background(if(moving)Color(0xFF75551E) else Color(0xFF253343),RoundedCornerShape(6.dp))
        .border(if(focused||moving)2.dp else 0.dp,if(moving)ColorGold else if(focused)Color.White else Color.Transparent,RoundedCornerShape(6.dp))
        .padding(4.dp),contentAlignment=Alignment.Center){
        Column(horizontalAlignment=Alignment.CenterHorizontally){
            if(channel.logo.isNotBlank())AsyncImage(channel.logo,null,Modifier.size(26.dp))
            Text(channel.name,fontSize=11.sp,maxLines=2,overflow=TextOverflow.Ellipsis,textAlign=TextAlign.Center,color=Color.White)
        }
    }
}

/** A single session owns playback; fullscreen only changes the view hosting that session. */
@Composable
private fun JaLiveTvPlayback(channel:JaLiveChannel,repository:JaLiveTvRepository,fullscreen:Boolean,
    onFullscreen:(Boolean)->Unit,onPlaying:(JaLiveChannel)->Unit,onAttached:(Boolean)->Unit) {
    var revision by remember(channel.id){mutableIntStateOf(0)}
    var resolved by remember(channel.id){mutableStateOf<JaLiveChannel?>(null)}
    var resolveError by remember(channel.id){mutableStateOf(false)}
    LaunchedEffect(channel.id,revision){
        resolved=null;resolveError=false
        resolved=try{repository.playbackChannel(channel,revision>0)}catch(e:CancellationException){throw e}catch(_:Exception){resolveError=true;null}
        Log.i("JA_LIVE_TV","SOURCES_READY channel=${channel.id} count=${resolved?.urls?.size}")
    }
    val ready=resolved
    if(ready==null){
        JaLivePresentation(fullscreen,{onFullscreen(false)}){
            Box(Modifier.fillMaxSize().background(Color.Black),contentAlignment=Alignment.Center){
                if(resolveError)Column(horizontalAlignment=Alignment.CenterHorizontally){
                    Text("Channel unavailable",color=Color.White,fontSize=12.sp)
                    TvFocusButton(onClick={revision++}){Text("Retry")}
                }else Text("Connecting…",color=Color.White,fontSize=12.sp)
            }
        }
    }else key(revision){JaLiveTvSession(ready,fullscreen,onRetry={revision++},
        onClose={onFullscreen(false)},onFullscreen={onFullscreen(true)},onPlaying=onPlaying,onAttached=onAttached)}
}

@Composable
private fun JaLivePresentation(fullscreen:Boolean,onClose:()->Unit,content:@Composable ()->Unit){
    if(fullscreen)Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
        val view=LocalView.current
        DisposableEffect(view){
            val window=(view.parent as? DialogWindowProvider)?.window
            window?.let{
                WindowCompat.getInsetsController(it,view).apply{
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
            onDispose{}
        }
        Box(Modifier.fillMaxSize().background(Color.Black)){content()}
    }else content()
}

private data class JaLiveReconnect(val target:Int,val reason:String,val delayMs:Long)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun JaLiveTvSession(channel:JaLiveChannel,fullscreen:Boolean,onRetry:()->Unit,onClose:()->Unit,
    onFullscreen:()->Unit,onPlaying:(JaLiveChannel)->Unit,onAttached:(Boolean)->Unit) {
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
    val reportPlaying by rememberUpdatedState(onPlaying)
    val reportAttached by rememberUpdatedState(onAttached)
    val player=remember(channel,source,generation){
        val headers=channel.headers[channel.urls[source]].orEmpty()
        val http=DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
            .setUserAgent(headers["User-Agent"]?:"JA/2.4.7").setDefaultRequestProperties(headers)
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
        reportAttached(true)
        JaMusicService.setVideoActive(true)
        var internalChange=false
        val listener=object:Player.Listener{
            override fun onIsPlayingChanged(isPlaying:Boolean){if(isPlaying)reportPlaying(channel)}
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
                if(!internalChange&&resumed&&(reason==Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
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
        onDispose{owner.lifecycle.removeObserver(observer);player.removeListener(listener);player.release();reportAttached(false);JaMusicService.setVideoActive(false)}
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
    JaLivePresentation(fullscreen,onClose){
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)){
        AndroidView(factory={PlayerView(it).apply{
            layoutParams=ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT)
            this.player=player;useController=fullscreen;keepScreenOn=true
            if(fullscreen)requestFocus() else {isFocusable=false;isFocusableInTouchMode=false;descendantFocusability=ViewGroup.FOCUS_BLOCK_DESCENDANTS}
        }},update={it.player=player},modifier=Modifier.fillMaxSize(),onReset=null,onRelease={it.player=null;it.keepScreenOn=false})
        if(!fullscreen&&error==null)Box(Modifier.fillMaxSize().clickable(onClick=onFullscreen)
            .semantics{contentDescription="Open ${channel.name} fullscreen"})
        if(recovery!=null)Text("Reconnecting live stream…",color=androidx.compose.ui.graphics.Color.White,
            modifier=Modifier.align(Alignment.TopCenter).background(androidx.compose.ui.graphics.Color(0xCC202832)).padding(12.dp))
        if(!fullscreen&&error!=null)Box(Modifier.fillMaxSize().background(Color(0xDD202832)).clickable(onClick=onRetry),contentAlignment=Alignment.Center){
            Text("Channel unavailable · Retry",Modifier.padding(6.dp),color=Color.White,fontSize=12.sp,textAlign=TextAlign.Center)
        }
        if(fullscreen)error?.let{message->Column(Modifier.align(Alignment.Center).background(androidx.compose.ui.graphics.Color(0xDD202832)).padding(20.dp),horizontalAlignment=Alignment.CenterHorizontally){
            Text(message,color=androidx.compose.ui.graphics.Color.White)
            TvFocusButton(onClick=onRetry){Text("Refresh sources and retry")}
            TvFocusButton(onClick=onClose){Text("Back to channels")}
        }}
    }
}
}
