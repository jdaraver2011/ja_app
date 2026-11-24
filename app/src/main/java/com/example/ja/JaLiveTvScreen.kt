package com.example.ja

import android.view.ViewGroup
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
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
import androidx.compose.ui.focus.focusProperties
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
    val previewFocus=FocusRequester()
    var previewRequest by mutableIntStateOf(0)
    fun focusPreview(){previewRequest++}
    val groupFocus=FocusRequester()
    val groupList=LazyListState()
    var previewChannel by mutableStateOf<JaLiveChannel?>(null)
    var playingChannelId by mutableStateOf<String?>(null)
    var timelineScale=0f
    var playerAttached by mutableStateOf(false)
    var fullscreen by mutableStateOf(false)
    var lastChannelId by mutableStateOf(prefs.getString("last-channel",null))
    init {
        if(!prefs.getBoolean("weather-channel-added",false)){
            favorites=favorites+JA_WEATHER_CHANNEL;favoriteOrder=listOf(JA_WEATHER_CHANNEL)+favoriteOrder.filter{it!=JA_WEATHER_CHANNEL}
            prefs.edit().putStringSet("ids",favorites).putString("order",org.json.JSONArray(favoriteOrder).toString())
                .putString("group-order:Favorites",org.json.JSONArray(favoriteOrder).toString()).putBoolean("weather-channel-added",true).apply()
        }
        JaLiveTvRules.views().forEach{group->
            val stored=runCatching { val a=org.json.JSONArray(prefs.getString("group-order:$group","[]"));(0 until a.length()).map{a.getString(it)} }.getOrDefault(emptyList())
            groupOrders[group]=if(group=="Favorites"&&stored.isEmpty())favoriteOrder else stored
        }
    }
    fun order(group:String,available:List<String>):List<String> {
        val stored=groupOrders.getOrPut(group){runCatching{val a=org.json.JSONArray(prefs.getString("group-order:$group","[]"));
            (0 until a.length()).map{a.getString(it)}}.getOrDefault(emptyList())}
        return JaLiveTvMoveSession.displayed(if(movingId!=null&&movingGroup==group)draftOrder else stored,available)
    }
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
        playingChannelId=channel.id
        if(lastChannelId!=channel.id){lastChannelId=channel.id;prefs.edit().putString("last-channel",channel.id).apply()}
    }
    fun tune(channel:JaLiveChannel,full:Boolean){
        finishMove(false);if(previewChannel?.id!=channel.id)playingChannelId=null;previewChannel=channel;fullscreen=full;focusedChannel=channel.id
        if(lastChannelId!=channel.id){lastChannelId=channel.id;prefs.edit().putString("last-channel",channel.id).apply()}
    }
    var appliedView:String?=null
    var view by mutableStateOf("United States")
    var channels by mutableStateOf<List<JaLiveChannel>>(listOf(jaWeatherChannel()))
    var catalogReady by mutableStateOf(false)
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
    fun reloadPersonalFavorites(){
        favorites=prefs.getStringSet("ids",emptySet())?.toSet()?:emptySet()
        favoriteOrder=runCatching{val a=org.json.JSONArray(prefs.getString("order","[]"));(0 until a.length()).map{a.getString(it)}}.getOrDefault(emptyList())
        groupOrders.clear()
        JaLiveTvRules.views().forEach{group->
            val saved=runCatching{val a=org.json.JSONArray(prefs.getString("group-order:$group","[]"));(0 until a.length()).map{a.getString(it)}}.getOrDefault(emptyList())
            groupOrders[group]=if(group=="Favorites"&&saved.isEmpty())favoriteOrder else saved
        }
    }
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
        if(!force&&catalogReady&&channels.isNotEmpty()&&now-lastAttempt<60_000)return
        lastAttempt=now
        // Owned by StreamingApp's scope, so opening a channel does not cancel the EPG download.
        refreshJob=scope.launch {
            loading=true;error=null
            try{
                val result=repository.channels(force)
                channels=listOf(jaWeatherChannel())+result.channels.filter{it.id!=JA_WEATHER_CHANNEL};saved=result.saved;verifiedAt=result.verifiedAt
                favoriteOrder=JaLiveTvOrder.reconcile(favoriteOrder,favorites,channels.map{it.id})
        groupOrders["Favorites"]=favoriteOrder
        prefs.edit().putString("group-order:Favorites",org.json.JSONArray(favoriteOrder).toString()).apply()
        saveOrder()
            }catch(e:CancellationException){throw e}catch(_:Exception){error="Channel catalog unavailable. Check your connection and retry."}
            finally{loading=false;catalogReady=true}
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
internal fun JaLiveTvScreen(state:JaLiveTvState,weather:JaWeatherState,onMainMenu:()->Unit,modifier:Modifier=Modifier) {
    JaScaledUi { JaLiveTvScreenContent(state,weather,onMainMenu,modifier) }
}

@Composable
private fun JaLiveTvScreenContent(state:JaLiveTvState,weather:JaWeatherState,onMainMenu:()->Unit,modifier:Modifier) {
    val scope=rememberCoroutineScope()
    val owner=LocalLifecycleOwner.current
    var resumed by remember{mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))}
    var groupsFocused by remember{mutableStateOf(false)}
    var guideFocused by remember{mutableStateOf(false)}
    var previewFocused by remember { mutableStateOf(false) }
    var searchFocused by remember{mutableStateOf(false)}
    var focusedGroup by remember{mutableIntStateOf(0)}
    var searchOpen by remember{mutableStateOf(false)}
    var menuChannel by remember{mutableStateOf<JaLiveChannel?>(null)}
    val menuGuard=remember{JaLiveTvGuideRules.MenuKeyGuard()}
    val searchFocus=remember{FocusRequester()}
    val jaButtonFocus=remember{FocusRequester()}
    val returnFocus=remember{FocusRequester()}
    DisposableEffect(owner){val observer=LifecycleEventObserver{_,_->resumed=owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)}
        owner.lifecycle.addObserver(observer);onDispose{owner.lifecycle.removeObserver(observer)}}
    // Give a new guide its first load, then wait for playback to release the player.
    val automaticRefreshAllowed = resumed && !state.fullscreen &&
        (!state.catalogReady || !state.playerAttached)
    LaunchedEffect(automaticRefreshAllowed) {
        if (automaticRefreshAllowed) while (isActive) {
            state.refresh()
            delay(900_000)
        }
    }
    LaunchedEffect(state.channels,state.fullscreen,state.catalogReady){
        if(state.catalogReady&&!state.fullscreen&&state.channels.isNotEmpty()&&
            (state.previewChannel==null||state.channels.none{it.id==state.previewChannel?.id})){
            val target=state.channels.firstOrNull{it.id==state.lastChannelId}
                ?:state.favoriteOrder.firstNotNullOfOrNull{id->state.channels.firstOrNull{it.id==id}}
                ?:state.channels.first()
            state.tune(target,false)
        }
    }
    var now by remember{mutableLongStateOf(System.currentTimeMillis())}
    LaunchedEffect(resumed,state.fullscreen){if(resumed&&!state.fullscreen)while(isActive){now=System.currentTimeMillis();delay(30_000)}}
    val programs=remember(state.guide,now/1_800_000,weather.zip){state.guide?.byChannel.orEmpty()+mapOf(JA_WEATHER_CHANNEL to jaWeatherPrograms(now,weather.zip))}
    val start=now/1_800_000*1_800_000
    val finish=start+86_400_000
    val groups=remember(state.channels){JaLiveTvRules.views(state.channels.flatMap{it.categories}.toSet())}
    // Index categories once per catalog update so switching Groups filters only that group.
    val channelsByGroup=remember(state.channels,groups){
        groups.associateWith{group->state.channels.filter{JaLiveTvRules.categoryMatches(group,it.categories)}}
    }
    val candidates=remember(channelsByGroup,state.view){channelsByGroup[state.view].orEmpty()}
    val epgTimeBucket=if(state.view=="With EPG")now/30_000 else 0L
    val available=remember(candidates,state.query,state.view,state.favorites,state.guide,epgTimeBucket){candidates.filter{channel->
        (channel.id!=JA_WEATHER_CHANNEL||state.view=="Favorites")
            &&(state.view!="Favorites"||channel.id in state.favorites)
            &&(state.view!="With EPG"||programs[channel.id].orEmpty().any{JaLiveTvRules.hasGuide(it.start,it.end,it.title,now,finish)})
            &&channel.name.contains(state.query,true)}}
    val channelMap=remember(available){available.associateBy{it.id}}
    val availableIds=remember(available){available.map{it.id}}
    val visibleIds=remember(state.view,availableIds,state.movingId,state.draftOrder){state.order(state.view,availableIds)}
    val visible=remember(channelMap,visibleIds){visibleIds.mapNotNull{channelMap[it]}}
    fun focusPlaying(){
        val id=state.playingChannelId?:state.previewChannel?.id?:JA_WEATHER_CHANNEL
        if(id !in channelMap){state.query="";state.view=if(id==JA_WEATHER_CHANNEL)"Favorites" else "United States"}
        state.returnChannel=id
    }
    var entryFocusRestored by remember { mutableStateOf(false) }
    LaunchedEffect(state.catalogReady,state.channels,state.fullscreen){
        if(!state.catalogReady||entryFocusRestored||state.fullscreen)return@LaunchedEffect
        val target=state.channels.firstOrNull{it.id==state.lastChannelId}
            ?:state.favoriteOrder.firstNotNullOfOrNull{id->state.channels.firstOrNull{it.id==id}}
            ?:state.channels.firstOrNull()
            ?:return@LaunchedEffect
        entryFocusRestored=true
        state.tune(target,false)
        val targetGroup=if(target.id==JA_WEATHER_CHANNEL)"Favorites"
            else state.view.takeIf{JaLiveTvRules.categoryMatches(it,target.categories)}
                ?:groups.firstOrNull{JaLiveTvRules.categoryMatches(it,target.categories)}
                ?: "United States"
        if(state.view!=targetGroup){state.view=targetGroup;withFrameNanos{}}
        state.returnChannel=target.id
    }
    LaunchedEffect(state.previewRequest){if(state.previewRequest>0){withFrameNanos{};runCatching{state.previewFocus.requestFocus()}}}
    fun focusGuide(){
        state.sidebarOpen=false
        state.returnChannel=state.focusedChannel?.takeIf{it in channelMap}?:visible.firstOrNull()?.id
    }
    fun selectGroup(group:String){
        if(state.view!=group){state.finishMove(false);state.view=group;state.returnChannel=null}
    }
    fun focusGroups(first:Boolean=false){
        state.sidebarOpen=true
        if(first)selectGroup(groups.first())
        scope.launch{
            state.groupList.scrollToItem(groups.indexOf(state.view).coerceAtLeast(0))
            withFrameNanos{};withFrameNanos{};runCatching{state.groupFocus.requestFocus()}
        }
    }
    fun focusJaButton(){
        // Cancel any pending channel-row restore so it cannot steal focus back from ‹ JA.
        state.returnChannel=null
        scope.launch{withFrameNanos{};runCatching{jaButtonFocus.requestFocus()}}
    }
    fun openMenu(channel:JaLiveChannel,remote:Boolean){
        state.finishMove(false);menuGuard.open(remote);menuChannel=channel
    }
    fun closeMenu(){val channel=menuChannel;menuChannel=null;state.returnChannel=channel?.id}
    fun play(channel:JaLiveChannel){state.tune(channel, state.previewChannel?.id == channel.id)}
    LaunchedEffect(state.view){if(state.appliedView!=state.view){
        if(state.returnChannel==null)state.list.scrollToItem(0);state.appliedView=state.view
    }}
    LaunchedEffect(state.movingId,visibleIds){if(state.movingId!=null&&state.movingId !in channelMap)state.finishMove(false)}
    LaunchedEffect(state.returnChannel,visibleIds){
        val id=state.returnChannel?:return@LaunchedEffect
        val index=visible.indexOfFirst{it.id==id}
        if(index<0){state.returnChannel=visible.firstOrNull()?.id;if(visible.isEmpty())focusGroups();return@LaunchedEffect}
        if(state.list.layoutInfo.visibleItemsInfo.none{it.key==id})state.list.scrollToItem(index)
        withFrameNanos{};withFrameNanos{}
        if(runCatching{returnFocus.requestFocus();true}.getOrDefault(false))state.returnChannel=null
    }
    BackHandler(!state.fullscreen&&menuChannel==null&&!searchOpen&&state.movingId==null&&(groupsFocused||guideFocused)){
        if(state.sidebarOpen&&groupsFocused){state.sidebarOpen=false;onMainMenu()} else focusGroups()
    }
    BackHandler(state.movingId!=null){state.finishMove(false)}
    val scroll=state.timeline
    Column(modifier.fillMaxSize().background(Color(0xFF101923)).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
        verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            TvFocusButton(onClick=onMainMenu,modifier=Modifier.focusRequester(jaButtonFocus),
                colors=ButtonDefaults.buttonColors(containerColor=Color(0xFF233747),contentColor=Color.White)) {
                Text("‹ JA")
            }
            Text("JA IPTV", color=ColorGold, style=MaterialTheme.typography.titleLarge, modifier=Modifier.weight(1f))
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).onPreviewKeyEvent{event->
        val key=event.nativeKeyEvent
        if(state.movingId==null)false else when(key.keyCode){
            KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN->{if(key.action==KeyEvent.ACTION_DOWN)state.moveChannel(if(key.keyCode==KeyEvent.KEYCODE_DPAD_UP)-1 else 1);true}
            KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_NUMPAD_ENTER->{
                if(key.action==KeyEvent.ACTION_UP){if(state.suppressPickUpRelease)state.suppressPickUpRelease=false else state.finishMove(true)};true
            }
            KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT->true
            else->false
        }
    }){
        val sidebarWidth=if(state.sidebarOpen)minOf(180.dp,maxWidth*0.32f) else 0.dp
        val guideWidth=(maxWidth-sidebarWidth-if(state.sidebarOpen)8.dp else 0.dp).coerceAtLeast(100.dp)
        val channelWidth=(guideWidth*0.28f).coerceIn(140.dp,250.dp)
        val timelineWidth=(guideWidth-channelWidth).coerceAtLeast(1.dp)
        val minuteWidth=JaLiveTvGuideRules.pixelsPerMinute(timelineWidth.value.toDouble()).toFloat().dp
        val totalWidth=minuteWidth*1440
        val density=androidx.compose.ui.platform.LocalDensity.current
        val minutePixels=with(density){minuteWidth.toPx()}
        LaunchedEffect(minutePixels){
            val old=state.timelineScale;state.timelineScale=minutePixels
            if(old>0&&kotlin.math.abs(old-minutePixels)>0.01f){
                val remapped=JaLiveTvGuideRules.remapScroll(scroll.value,old.toDouble(),minutePixels.toDouble())
                withFrameNanos{};scroll.scrollTo(remapped)
            }
        }
        val firstMinute by remember(scroll,minutePixels){derivedStateOf{((scroll.value/minutePixels)/15).toInt()*15}}
        val previewWidth=minOf(300.dp,guideWidth*0.52f,maxHeight*0.28f*(16f/9))
        val previewHeight=previewWidth*(9f/16)
        Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            if(state.sidebarOpen)Column(Modifier.width(sidebarWidth).fillMaxHeight().background(Color(0xFF1B2735),RoundedCornerShape(6.dp))
                .onFocusChanged{groupsFocused=it.hasFocus}.focusGroup()
                .onPreviewKeyEvent{event->
                    val key=event.nativeKeyEvent
                    if(key.keyCode==KeyEvent.KEYCODE_DPAD_UP&&!searchFocused&&focusedGroup==0){
                        if(key.action==KeyEvent.ACTION_DOWN)focusJaButton()
                        true
                    }else{
                        val action=JaLiveTvGuideRules.groupKey(key.keyCode,focusedGroup,groups.size,searchFocused)
                        if(action==JaLiveTvGuideRules.DEFAULT)false else {
                            if(key.action==KeyEvent.ACTION_DOWN)when(action){
                                JaLiveTvGuideRules.SEARCH->runCatching{searchFocus.requestFocus()}
                                JaLiveTvGuideRules.FIRST_GROUP->focusGroups(true)
                                JaLiveTvGuideRules.GUIDE->focusGuide()
                            };true
                        }
                    }
                }){
                Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){
                    Text("Groups",Modifier.weight(1f),color=ColorGold,fontSize=16.sp)
                    var focused by remember{mutableStateOf(false)}
                    IconButton(onClick={searchOpen=true},modifier=Modifier.size(40.dp).focusRequester(searchFocus)
                        .onFocusChanged{focused=it.isFocused;searchFocused=it.isFocused}
                        .border(if(focused)2.dp else 0.dp,if(focused)Color.White else Color.Transparent,RoundedCornerShape(6.dp))
                        .semantics{contentDescription="Search channels"}){
                        Canvas(Modifier.size(20.dp)){
                            val stroke=2.dp.toPx();val color=if(state.query.isNotBlank())ColorGold else Color.White
                            drawCircle(color,radius=size.minDimension*0.29f,center=Offset(size.width*0.40f,size.height*0.40f),style=Stroke(stroke))
                            drawLine(color,Offset(size.width*0.62f,size.height*0.62f),Offset(size.width*0.91f,size.height*0.91f),strokeWidth=stroke,cap=StrokeCap.Round)
                        }
                    }
                }
                LazyColumn(state=state.groupList,modifier=Modifier.fillMaxWidth().weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp),contentPadding=PaddingValues(6.dp)){
                    items(groups, key = { it }, contentType = { "channel-group" }){group->
                        TvFocusButton(onClick={selectGroup(group)},modifier=Modifier.fillMaxWidth()
                            .then(if(state.view==group)Modifier.focusRequester(state.groupFocus) else Modifier)
                            .onFocusChanged{if(it.isFocused){focusedGroup=groups.indexOf(group);searchFocused=false;selectGroup(group)}},
                            colors=ButtonDefaults.buttonColors(containerColor=if(state.view==group)ColorGold else Color(0xFF263747),
                                contentColor=if(state.view==group)Color.Black else Color.White)){
                            Text(group,fontSize=12.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().onFocusChanged{guideFocused=it.hasFocus}.focusGroup(),verticalArrangement=Arrangement.spacedBy(6.dp)){
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                    Box(Modifier.width(previewWidth).height(previewHeight).background(Color.Black,RoundedCornerShape(6.dp)).clipToBounds()
                        .focusRequester(state.previewFocus).onFocusChanged { previewFocused=it.isFocused }.border(if(previewFocused)2.dp else 0.dp,Color.White).onPreviewKeyEvent{event->
                            when(event.nativeKeyEvent.keyCode){
                                KeyEvent.KEYCODE_DPAD_DOWN->{if(event.nativeKeyEvent.action==KeyEvent.ACTION_DOWN)focusPlaying();true}
                                KeyEvent.KEYCODE_DPAD_UP->{if(event.nativeKeyEvent.action==KeyEvent.ACTION_DOWN)focusJaButton();true}
                                KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER->{if(event.nativeKeyEvent.action==KeyEvent.ACTION_UP)state.fullscreen=true;true}
                                else->false
                            }
                        }.focusable()) {
                        val channel=state.previewChannel
                        if(channel==null)Text("Select a channel",Modifier.align(Alignment.Center),color=Color.LightGray,fontSize=12.sp)
                        else if(channel.id==JA_WEATHER_CHANNEL){
                            JaLivePresentation(state.fullscreen,{state.fullscreen=false;state.returnChannel=channel.id}){
                                JaWeatherStarView(weather,Modifier.fillMaxSize(),onReady={state.playbackStarted(channel)})
                            }
                        }
                        else key(channel.id){JaLiveTvPlayback(channel,state.repository,state.fullscreen,
                            onFullscreen={state.fullscreen=it;if(!it)state.returnChannel=channel.id.takeIf{id->id in channelMap}?:visible.firstOrNull()?.id},
                            onPlaying={state.playbackStarted(it)},onAttached={state.playerAttached=it})}
                    }
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){
                        Text(state.previewChannel?.name?:"IPTV",color=Color.White,fontSize=16.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                        val current=programs[state.previewChannel?.id].orEmpty().firstOrNull{it.start<=now&&it.end>now}
                        Text(current?.title?:"Live television",color=Color.LightGray,fontSize=12.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                        current?.let{Text("${time(it.start)}–${time(it.end)}",color=Color.LightGray,fontSize=11.sp)}
                        Text("${state.view} · ${visible.size} channels",Modifier.clickable{focusGroups()}.padding(vertical=4.dp),color=ColorGold,fontSize=11.sp)
                        if(state.query.isNotBlank())Text("Search: ${state.query}",color=ColorGold,fontSize=11.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                        if(state.movingId!=null)Text("Moving: ${channelMap[state.movingId]?.name.orEmpty()}",color=ColorGold,fontSize=12.sp,maxLines=2)
                        else if(state.loading||state.guideLoading)Text(if(state.loading)"Updating channels…" else "Updating guide…",fontSize=11.sp,color=Color.LightGray)
                    }
                }
                state.error?.let{Text(it,color=Color(0xFFFFAAAA),fontSize=11.sp)}
                state.guideError?.let{Text(it,color=Color.LightGray,fontSize=11.sp)}
                if(visible.isEmpty()){
                    Text(if(state.guideLoading&&state.view=="With EPG")"Loading program schedules…" else "No matching channels",color=Color.LightGray,fontSize=12.sp)
                    if(!state.loading&&!state.guideLoading)TvFocusButton(onClick={state.refresh(true)}){Text("Refresh channels")}
                }
                Row(Modifier.fillMaxWidth().height(28.dp).background(Color(0xFF314254)),verticalAlignment=Alignment.CenterVertically){
                    Text("Channel",Modifier.width(channelWidth).padding(start=6.dp),fontSize=12.sp,color=Color.White)
                    Row(Modifier.weight(1f).horizontalScroll(scroll)){
                        repeat(48){slot->Text(time(start+slot*1_800_000),Modifier.width(minuteWidth*30).padding(start=4.dp),fontSize=12.sp,maxLines=1,color=Color.White)}
                    }
                }
                LazyColumn(Modifier.fillMaxWidth().weight(1f),state=state.list,verticalArrangement=Arrangement.spacedBy(2.dp),contentPadding=PaddingValues(bottom=8.dp)){
                    items(visible, key = { it.id }, contentType = { "live-channel" }){channel->
                        val visibleStart=start+(firstMinute-15).coerceAtLeast(0)*60_000L
                        val visibleEnd=(start+(firstMinute+JaLiveTvGuideRules.WINDOW_MINUTES+15)*60_000L).coerceAtMost(finish)
                        val channelPrograms=programs[channel.id].orEmpty()
                        val allEntries=remember(channelPrograms,start,finish){channelPrograms.filter{it.end>start&&it.start<finish}}
                        val entries=remember(allEntries,visibleStart,visibleEnd){
                            val first=allEntries.indexOfFirst{it.end>visibleStart};val last=allEntries.indexOfLast{it.start<visibleEnd}
                            if(first<0||last<first)emptyList() else allEntries.subList((first-1).coerceAtLeast(0),(last+2).coerceAtMost(allEntries.size))
                        }
                        val playing=state.playingChannelId==channel.id
                        Row(Modifier.fillMaxWidth().height(48.dp),verticalAlignment=Alignment.CenterVertically){
                            JaLiveChannelButton(channel,playing,channel.id in state.favorites,state.movingId==channel.id,
                                modifier=Modifier.width(channelWidth).fillMaxHeight().then(if(state.returnChannel==channel.id)Modifier.focusRequester(returnFocus) else Modifier),
                                onFocus={state.focusedChannel=channel.id},onPlay={play(channel)},onMenu={remote->openMenu(channel,remote)},
                                onLeft={focusGroups()},onDragMove={state.moveChannel(it)},onDrop={state.finishMove(true)},onCancel={state.finishMove(false)})
                            Box(Modifier.weight(1f).fillMaxHeight().clipToBounds().horizontalScroll(scroll)){
                                Box(Modifier.width(totalWidth).fillMaxHeight().background(Color(0xFF1D2938))){
                                    if(allEntries.isEmpty())JaLiveGuideCell("Guide unavailable",playing,false,
                                        Modifier.width(timelineWidth).fillMaxHeight(),{play(channel)},{remote->openMenu(channel,remote)})
                                    entries.forEach{program->key(program.channel,program.start,program.end,program.title){
                                        val left=minuteWidth*((program.start.coerceAtLeast(start)-start)/60000f)
                                        val width=minuteWidth*((program.end.coerceAtMost(finish)-program.start.coerceAtLeast(start))/60000f)
                                        JaLiveGuideCell(program.title,playing,program.start<=now&&program.end>now,
                                            Modifier.offset(x=left).width(width.coerceAtLeast(8.dp)).fillMaxHeight(),{play(channel)},{remote->openMenu(channel,remote)})
                                    }}
                                    Box(Modifier.offset(x=minuteWidth*((now-start)/60_000f)).width(1.dp).fillMaxHeight().background(ColorGold.copy(alpha=0.7f)))
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }
    if(searchOpen)AlertDialog(onDismissRequest={searchOpen=false;focusGroups()},title={Text("Search channels")},
        text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(state.view,color=ColorGold)
            JaEntryField(state.query,{state.finishMove(false);state.returnChannel=null;state.query=it},Modifier.fillMaxWidth(),label={Text("Channel name")},
                initiallyEditing=true,keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(imeAction=androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions=androidx.compose.foundation.text.KeyboardActions(onSearch={searchOpen=false;focusGroups()}))
        }},confirmButton={TextButton(onClick={searchOpen=false;focusGroups()}){Text("Done")}},
        dismissButton={TextButton(onClick={state.query="";state.returnChannel=null}){Text("Clear")}})
    menuChannel?.let{channel->
        JaLiveChannelMenu(channel,state.favorites.contains(channel.id),menuGuard,
            canReorder=channel.id in visibleIds,canRefresh=!state.loading&&!state.guideLoading,
            onClose={closeMenu()},onWatch={menuChannel=null;play(channel)},
            onFavorite={state.favorite(channel.id);closeMenu()},
            onReorder={menuChannel=null;state.beginMove(channel.id,visibleIds,false)},
            onRefresh={closeMenu();state.refresh(true)})
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Modifier.jaLiveSelect(onClick:()->Unit,onMenu:(Boolean)->Unit,onFocus:(Boolean)->Unit={}):Modifier{
    val scope=rememberCoroutineScope()
    var hold by remember{mutableStateOf<Job?>(null)}
    var longPress by remember{mutableStateOf(false)}
    var focused by remember{mutableStateOf(false)}
    val menu by rememberUpdatedState(onMenu);val click by rememberUpdatedState(onClick);val focus by rememberUpdatedState(onFocus)
    DisposableEffect(Unit){onDispose{hold?.cancel()}}
    return onFocusChanged{focused=it.isFocused;focus(it.isFocused);if(!it.isFocused)hold?.cancel()}
        .onPreviewKeyEvent{event->val key=event.nativeKeyEvent
            if(key.keyCode !in listOf(KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_NUMPAD_ENTER))false else{
                if(key.action==KeyEvent.ACTION_DOWN&&key.repeatCount==0){
                    longPress=false;hold?.cancel();hold=scope.launch{delay(android.view.ViewConfiguration.getLongPressTimeout().toLong());longPress=true;menu(true)}
                }else if(key.action==KeyEvent.ACTION_UP){hold?.cancel();if(!longPress&&!key.isCanceled&&focused)click()}
                true
            }
        }.combinedClickable(onClick=onClick,onLongClick={onMenu(false)})
}

@Composable
private fun JaLiveChannelButton(channel:JaLiveChannel,playing:Boolean,favorite:Boolean,moving:Boolean,modifier:Modifier,
    onFocus:()->Unit,onPlay:()->Unit,onMenu:(Boolean)->Unit,onLeft:()->Unit,onDragMove:(Int)->Unit,onDrop:()->Unit,onCancel:()->Unit){
    var focused by remember{mutableStateOf(false)}
    val threshold=with(androidx.compose.ui.platform.LocalDensity.current){48.dp.toPx()}
    val move by rememberUpdatedState(onDragMove);val drop by rememberUpdatedState(onDrop);val cancel by rememberUpdatedState(onCancel)
    val contentColor=if(playing&&!moving)Color.Black else Color.White
    Row(modifier.onPreviewKeyEvent{event->
        if(event.nativeKeyEvent.keyCode==KeyEvent.KEYCODE_DPAD_LEFT){if(event.nativeKeyEvent.action==KeyEvent.ACTION_DOWN)onLeft();true}else false
    }.jaLiveSelect(onPlay,onMenu){focused=it;if(it)onFocus()}
        .then(if(moving)Modifier.pointerInput(channel.id,threshold){var distance=0f
            detectVerticalDragGestures(onDragEnd={drop()},onDragCancel={cancel()}){change,dy->
                change.consume();distance+=dy
                while(kotlin.math.abs(distance)>=threshold){val direction=if(distance>0)1 else -1;move(direction);distance-=threshold*direction}
            }
        } else Modifier)
        .background(if(moving)Color(0xFF75551E) else if(playing)ColorGold else Color(0xFF253343),RoundedCornerShape(4.dp))
        .border(if(focused||moving)2.dp else 0.dp,if(moving)ColorGold else if(focused)Color.White else Color.Transparent,RoundedCornerShape(4.dp))
        .padding(horizontal=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
        Text(if(favorite)"★" else "",Modifier.width(12.dp),color=if(playing&&!moving)Color.Black else ColorGold,fontSize=12.sp)
        if(channel.logo.isNotBlank())AsyncImage(channel.logo,null,Modifier.size(28.dp))
        Text(if(channel.id==JA_WEATHER_CHANNEL)"1 · ${channel.name}" else channel.name,Modifier.weight(1f),fontSize=13.sp,maxLines=1,overflow=TextOverflow.Ellipsis,color=contentColor)
    }
}

@Composable
private fun JaLiveGuideCell(title:String,playing:Boolean,current:Boolean,modifier:Modifier,onClick:()->Unit,onMenu:(Boolean)->Unit){
    var focused by remember{mutableStateOf(false)}
    Box(modifier.padding(end=2.dp).jaLiveSelect(onClick,onMenu){focused=it}
        .background(if(playing)Color(0xFF5A4827) else if(current)Color(0xFF38516B) else Color(0xFF283747),RoundedCornerShape(3.dp))
        .border(if(focused)2.dp else 0.dp,if(focused)Color.White else Color.Transparent,RoundedCornerShape(3.dp))
        .padding(horizontal=8.dp),contentAlignment=Alignment.CenterStart){
        Text(title,color=Color.White,fontSize=13.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
    }
}

@Composable
private fun JaLiveChannelMenu(channel:JaLiveChannel,favorite:Boolean,guard:JaLiveTvGuideRules.MenuKeyGuard,
    canReorder:Boolean,canRefresh:Boolean,onClose:()->Unit,onWatch:()->Unit,onFavorite:()->Unit,onReorder:()->Unit,onRefresh:()->Unit){
    val first=remember{FocusRequester()}
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)){
        LaunchedEffect(Unit){withFrameNanos{};first.requestFocus()}
        Box(Modifier.fillMaxSize().onPreviewKeyEvent{event->val key=event.nativeKeyEvent
            if(key.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_NUMPAD_ENTER))
                guard.consume(key.action==KeyEvent.ACTION_DOWN,key.action==KeyEvent.ACTION_UP,key.repeatCount)
            else if(key.keyCode==KeyEvent.KEYCODE_DPAD_LEFT){if(key.action==KeyEvent.ACTION_DOWN)onClose();true}else false
        }){
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=0.45f)).focusProperties{canFocus=false}.clickable(onClick=onClose))
            Column(Modifier.align(Alignment.CenterEnd).widthIn(max=340.dp).fillMaxWidth().fillMaxHeight().background(Color(0xFF1B2735))
                .padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                Text("Channel options",color=ColorGold,fontSize=20.sp)
                Text(channel.name,color=Color.White,fontSize=16.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                val colors=ButtonDefaults.buttonColors(containerColor=Color(0xFF2A3A4B),contentColor=Color.White)
                TvFocusButton(onClick=onWatch,modifier=Modifier.fillMaxWidth().focusRequester(first),colors=colors){Text("Watch fullscreen")}
                TvFocusButton(onClick=onFavorite,modifier=Modifier.fillMaxWidth(),colors=colors){Text(if(favorite)"Remove from Favorites" else "Add to Favorites")}
                TvFocusButton(onClick=onReorder,enabled=canReorder,modifier=Modifier.fillMaxWidth(),colors=colors){Text("Reorder channels")}
                TvFocusButton(onClick=onRefresh,enabled=canRefresh,modifier=Modifier.fillMaxWidth(),colors=colors){Text("Refresh channels and guide")}
                TvFocusButton(onClick=onClose,modifier=Modifier.fillMaxWidth(),colors=colors){Text("Close")}
            }
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
internal fun JaLivePresentation(fullscreen:Boolean,onClose:()->Unit,content:@Composable ()->Unit){
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
            .setUserAgent(headers["User-Agent"]?:"JA/2.5.0").setDefaultRequestProperties(headers)
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
