package com.example.ja

import android.app.Activity
import android.net.Uri
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
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
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.common.VideoSize
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val sourceGroupOrders=mutableStateMapOf<String,List<String>>()
    var movingId by mutableStateOf<String?>(null)
    var draftOrder by mutableStateOf<List<String>>(emptyList())
    private var movingGroup=""
    var movingGroupId by mutableStateOf<String?>(null)
    var draftGroupOrder by mutableStateOf<List<String>>(emptyList())
    private var movingGroupSource=""
    var suppressPickUpRelease=false
    var sidebarOpen by mutableStateOf(true)
    var focusedChannel:String?=null
    val mainFocus=FocusRequester()
    val previewFocus=FocusRequester()
    var previewRequest by mutableIntStateOf(0)
    fun focusPreview(){previewRequest++}
    val groupFocus=FocusRequester()
    val groupList=LazyListState()
    val sourceFocus=FocusRequester()
    val sourceList=LazyListState()
    var source by mutableStateOf("Favorites")
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
                .putString("group-order:All Favorites",org.json.JSONArray(favoriteOrder).toString()).putBoolean("weather-channel-added",true).apply()
        }
        JaLiveTvRules.views().forEach{group->
            val stored=runCatching { val a=org.json.JSONArray(prefs.getString("group-order:$group","[]"));(0 until a.length()).map{a.getString(it)} }.getOrDefault(emptyList())
            val legacy=if(group=="All Favorites")runCatching{val a=org.json.JSONArray(prefs.getString("group-order:Favorites","[]"));(0 until a.length()).map{a.getString(it)}}.getOrDefault(emptyList()) else emptyList()
            groupOrders[group]=if(group=="All Favorites"&&stored.isEmpty())favoriteOrder.ifEmpty{legacy} else stored
        }
        val legacyBaseballOrder=prefs.getString("group-order:Baseball USA",null)
        if(!legacyBaseballOrder.isNullOrBlank()&&prefs.getString("group-order:Baseball","[]")=="[]"){
            prefs.edit().putString("group-order:Baseball",legacyBaseballOrder).remove("group-order:Baseball USA").apply()
        }
    }
    fun order(group:String,available:List<String>):List<String> {
        val stored=groupOrders.getOrPut(group){runCatching{val a=org.json.JSONArray(prefs.getString("group-order:$group","[]"));
            (0 until a.length()).map{a.getString(it)}}.getOrDefault(emptyList())}
        return JaLiveTvMoveSession.displayed(if(movingId!=null&&movingGroup==group)draftOrder else stored,available)
    }
    private fun sourceGroupOrder(source:String):List<String> = sourceGroupOrders.getOrPut(source){runCatching{
        val a=org.json.JSONArray(prefs.getString("source-group-order:$source","[]"))
        val stored=(0 until a.length()).map{a.getString(it)}
        val migrated=if(source=="NTV")stored.mapNotNull{group->when(group){
            "NFL USA","NTV NFL USA","Basketball USA","NTV Basketball USA"->null
            "Baseball USA"->"Baseball"
            else->group
        }}.distinct() else stored
        if(migrated!=stored)prefs.edit().putString("source-group-order:$source",org.json.JSONArray(migrated).toString()).apply()
        migrated
    }.getOrDefault(emptyList())}
    fun beginGroupMove(id:String,visible:List<String>){
        if(movingGroupId!=null||id !in visible)return
        movingGroupId=id;draftGroupOrder=visible.toList();movingGroupSource=source
    }
    fun moveGroup(direction:Int){
        val id=movingGroupId?:return
        draftGroupOrder=JaLiveTvOrder.move(draftGroupOrder,id,direction)
    }
    fun finishGroupMove(save:Boolean){
        val id=movingGroupId?:return
        val source=movingGroupSource
        if(save){
            val available=catalogIndex.groupsBySource[source].orEmpty()
            val merged=JaLiveTvMoveSession.merge(sourceGroupOrder(source),available,draftGroupOrder)
            sourceGroupOrders[source]=merged
            prefs.edit().putString("source-group-order:$source",org.json.JSONArray(merged).toString()).apply()
        }
        movingGroupId=null;draftGroupOrder=emptyList();movingGroupSource=""
        if(id in sourceGroups)scope.launch{groupList.scrollToItem(sourceGroups.indexOf(id).coerceAtLeast(0))}
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
            val available=channels.filter{JaLiveTvRules.categoryMatches(movingGroup,it.categories)&&(movingGroup!="All Favorites"||it.id in favorites)}.map{it.id}
            val merged=JaLiveTvMoveSession.merge(groupOrders[movingGroup].orEmpty(),available,draftOrder)
            groupOrders[movingGroup]=merged
            val edit=prefs.edit().putString("group-order:$movingGroup",org.json.JSONArray(merged).toString())
            if(movingGroup=="All Favorites"){favoriteOrder=merged;edit.putString("order",org.json.JSONArray(merged).toString())}
            edit.apply()
        }
        moveSession.clear();movingId=null;draftOrder=emptyList();suppressPickUpRelease=false;returnChannel=id
    }
    fun stopPlayback(){
        adultOnlyUnlocked=false
        fullscreen=false
        previewChannel=null
        playingChannelId=null
        focusedChannel=null
        returnChannel=null
    }
    fun playbackStarted(channel:JaLiveChannel){
        playingChannelId=channel.id
        if(lastChannelId!=channel.id){lastChannelId=channel.id;prefs.edit().putString("last-channel",channel.id).apply()}
    }
    fun lockAdultOnly(){
        adultOnlyUnlocked=false
        if(previewChannel?.categories?.contains("ntv-adult-only")==true)stopPlayback()
    }
    fun tune(channel:JaLiveChannel,full:Boolean){
        finishMove(false);if(previewChannel?.id!=channel.id)playingChannelId=null;previewChannel=channel;fullscreen=full;focusedChannel=channel.id
        if(lastChannelId!=channel.id){lastChannelId=channel.id;prefs.edit().putString("last-channel",channel.id).apply()}
    }
    var appliedView:String?=null
    var view by mutableStateOf("All Favorites")
    var adultOnlyUnlocked by mutableStateOf(false)
    private data class CatalogIndex(
        val channels: List<JaLiveChannel>,
        val groupsBySource: Map<String,List<String>>,
        val channelsBySourceGroup: Map<Pair<String,String>,List<JaLiveChannel>>
    )
    private var catalogIndex by mutableStateOf(
        CatalogIndex(listOf(jaWeatherChannel()),emptyMap(),emptyMap())
    )
    val channels get() = catalogIndex.channels
    val sourceGroups get() = JaLiveTvMoveSession.displayed(
        if(movingGroupId!=null&&movingGroupSource==source)draftGroupOrder else sourceGroupOrder(source),
        catalogIndex.groupsBySource[source].orEmpty())
    val channelsBySourceGroup get() = catalogIndex.channelsBySourceGroup
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
    private var indexedCatalogChannels: List<JaLiveChannel>? = null
    val list=LazyListState()
    val groups=androidx.compose.foundation.ScrollState(0)
    val timeline=androidx.compose.foundation.ScrollState(0)
    private fun saveOrder(){prefs.edit().putStringSet("ids",favorites.toSet()).putString("order",org.json.JSONArray(favoriteOrder).toString()).apply()}
    fun reloadPersonalFavorites(){
        favorites=prefs.getStringSet("ids",emptySet())?.toSet()?:emptySet()
        favoriteOrder=runCatching{val a=org.json.JSONArray(prefs.getString("order","[]"));(0 until a.length()).map{a.getString(it)}}.getOrDefault(emptyList())
        groupOrders.clear();sourceGroupOrders.clear()
        JaLiveTvRules.views().forEach{group->
            val saved=runCatching{val a=org.json.JSONArray(prefs.getString("group-order:$group","[]"));(0 until a.length()).map{a.getString(it)}}.getOrDefault(emptyList())
            val legacy=if(group=="All Favorites")runCatching{val a=org.json.JSONArray(prefs.getString("group-order:Favorites","[]"));(0 until a.length()).map{a.getString(it)}}.getOrDefault(emptyList()) else emptyList()
            groupOrders[group]=if(group=="All Favorites"&&saved.isEmpty())favoriteOrder.ifEmpty{legacy} else saved
        }
    }
    fun favorite(id:String){
        favorites=if(id in favorites)favorites-id else favorites+id
        favoriteOrder=JaLiveTvOrder.reconcile(favoriteOrder,favorites,channels.map{it.id})
        groupOrders["All Favorites"]=favoriteOrder
        prefs.edit().putString("group-order:All Favorites",org.json.JSONArray(favoriteOrder).toString()).apply()
        saveOrder()
    }
    fun refresh(force:Boolean=false){
        if(refreshJob?.isActive==true)return
        val now=SystemClock.elapsedRealtime()
        if(!force&&catalogReady&&channels.isNotEmpty()&&now-lastAttempt<60_000)return
        lastAttempt=now
        // Owned by StreamingApp's scope, so opening a channel does not cancel the EPG download.
        refreshJob=scope.launch {
            // Keep the cached lineup visible during daily/background checks. Only show the
            // initial loading state before there is a catalog to display.
            if(!catalogReady||channels.isEmpty())loading=true
            error=null
            try{
                suspend fun applyCatalog(result:JaLiveChannels){
                    if (indexedCatalogChannels !== result.channels) {
                        val indexStart = SystemClock.elapsedRealtime()
                        catalogIndex = withContext(Dispatchers.Default) {
                            val channels = listOf(jaWeatherChannel()) + result.channels.filter { it.id != JA_WEATHER_CHANNEL } +
                                jaDaddyLiveUsPilot.map{it.copy(categories=it.categories+"source-daddylive")}
                            val allSourceGroups=JaLiveTvRules.sources().associateWith{sourceName->
                                val sourceChannels=channels.filter{JaLiveTvRules.sourceMatches(sourceName,it.categories)}
                                JaLiveTvRules.groupsForSource(sourceName,sourceChannels.flatMap{it.categories}.toSet())
                            }
                            val channelGroups=allSourceGroups.flatMap{(sourceName,sourceViews)->sourceViews.map{sourceName to it}}
                            val sourceIndex=channelGroups.associateWith{(sourceName,group)->channels.filter{channel->
                                JaLiveTvRules.sourceMatches(sourceName,channel.categories)&&
                                    (sourceName=="Favorites"&&group=="All Favorites"||
                                        sourceName!="Favorites"&&(group=="With EPG"||JaLiveTvRules.categoryMatches(group,channel.categories)))
                            }}
                            val sourceGroups=allSourceGroups.mapValues{(sourceName,sourceViews)->sourceViews.filter{group->
                                (sourceName=="Favorites"&&group=="All Favorites")||sourceIndex[sourceName to group].orEmpty().isNotEmpty()
                            }}
                            CatalogIndex(channels,sourceGroups,sourceIndex)
                        }
                        indexedCatalogChannels = result.channels
                        android.util.Log.i("JA_IPTV_PERF", "CATALOG_INDEX ms=${SystemClock.elapsedRealtime() - indexStart}")
                    }
                    saved=result.saved;verifiedAt=result.verifiedAt
                    catalogReady=true
                }
                val result=repository.channels(force){cached->
                    applyCatalog(cached)
                    repository.cachedGuideForDisplay(channels)?.let{guide=it}
                }
                applyCatalog(result)
                favoriteOrder=JaLiveTvOrder.reconcile(favoriteOrder,favorites,channels.map{it.id})
                groupOrders["All Favorites"]=favoriteOrder
                prefs.edit().putString("group-order:All Favorites",org.json.JSONArray(favoriteOrder).toString()).apply()
                saveOrder()
            }catch(e:CancellationException){throw e}catch(_:Exception){error="Channel catalog unavailable. Check your connection and retry."}
            finally{loading=false;catalogReady=true}
            if(channels.isEmpty())return@launch
            // A saved schedule remains usable while the once-daily refresh runs.
            if(guide==null)guideLoading=true
            guideError=null
            val cacheStart = SystemClock.elapsedRealtime()
            val cachedGuide = repository.cachedGuideForDisplay(channels)
            cachedGuide?.let{guide=it}
            android.util.Log.i("JA_IPTV_PERF", "GUIDE_CACHE hit=${cachedGuide != null} ms=${SystemClock.elapsedRealtime() - cacheStart}")
            val guideStart = SystemClock.elapsedRealtime()
            try{
                guide=repository.guide(channels,force){partial->guide=partial}
                android.util.Log.i("JA_IPTV_PERF", "GUIDE_READY ms=${SystemClock.elapsedRealtime() - guideStart}")
            }
            catch(e:CancellationException){throw e}
            catch(_:Exception){guideError="Guide unavailable. Channels can still be played."}
            finally{guideLoading=false}
        }
    }
}

private val ColorGold=Color(0xFFE5A43B)
private val liveTimeFormat=DateTimeFormatter.ofPattern("h:mm a",Locale.US)
private fun time(value:Long):String=liveTimeFormat.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(value))
private enum class JaNtvPinDialog { CREATE, UNLOCK_ADULT }

@Composable
internal fun JaLiveTvScreen(state:JaLiveTvState,weather:JaWeatherState,onMainMenu:()->Unit,
    matchTvFrameRate:Boolean=false,matchTvResolution:Boolean=false,matchStreamQuality:Boolean=false,
    playerTelemetryEnabled:Boolean=BuildConfig.DEBUG,hlsRequestInspectionEnabled:Boolean=BuildConfig.DEBUG,
    modifier:Modifier=Modifier) {
    JaScaledUi { JaLiveTvScreenContent(state,weather,onMainMenu,matchTvFrameRate,matchTvResolution,
        matchStreamQuality,playerTelemetryEnabled,hlsRequestInspectionEnabled,modifier) }
}

@Composable
private fun JaLiveTvScreenContent(state:JaLiveTvState,weather:JaWeatherState,onMainMenu:()->Unit,
    matchTvFrameRate:Boolean,matchTvResolution:Boolean,matchStreamQuality:Boolean,
    playerTelemetryEnabled:Boolean,hlsRequestInspectionEnabled:Boolean,modifier:Modifier) {
    val scope=rememberCoroutineScope()
    val owner=LocalLifecycleOwner.current
    val appContext=LocalContext.current
    val pinStore=remember(appContext){JaIptvPinStore(appContext)}
    var resumed by remember{mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))}
    var groupsFocused by remember{mutableStateOf(false)}
    var sourcesFocused by remember{mutableStateOf(false)}
    var guideFocused by remember{mutableStateOf(false)}
    var previewFocused by remember { mutableStateOf(false) }
    var searchFocused by remember{mutableStateOf(false)}
    var focusedGroup by remember{mutableIntStateOf(0)}
    var focusedSource by remember{mutableIntStateOf(0)}
    var searchOpen by remember{mutableStateOf(false)}
    var menuChannel by remember{mutableStateOf<JaLiveChannel?>(null)}
    var pinDialog by remember{mutableStateOf<JaNtvPinDialog?>(null)}
    val menuGuard=remember{JaLiveTvGuideRules.MenuKeyGuard()}
    val searchFocus=remember{FocusRequester()}
    val jaButtonFocus=remember{FocusRequester()}
    val returnFocus=remember{FocusRequester()}
    DisposableEffect(state){onDispose{state.lockAdultOnly();state.stopPlayback()}}
    DisposableEffect(owner){val observer=LifecycleEventObserver{_,_->
        resumed=owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        if(!resumed)state.lockAdultOnly()
    }
        owner.lifecycle.addObserver(observer);onDispose{owner.lifecycle.removeObserver(observer)}}
    // Give a new guide its first load, then wait for playback to release the player.
    val automaticRefreshAllowed = resumed && !state.fullscreen &&
        (!state.catalogReady || !state.playerAttached)
    LaunchedEffect(automaticRefreshAllowed) {
        if (automaticRefreshAllowed) while (isActive) {
            withFrameNanos {}
            state.refresh()
            delay(900_000)
        }
    }
    var now by remember{mutableLongStateOf(System.currentTimeMillis())}
    LaunchedEffect(resumed,state.fullscreen){if(resumed&&!state.fullscreen)while(isActive){now=System.currentTimeMillis();delay(30_000)}}
    val programs=remember(state.guide,now/1_800_000,weather.zip){state.guide?.byChannel.orEmpty()+mapOf(JA_WEATHER_CHANNEL to jaWeatherPrograms(now,weather.zip))}
    val start=now/1_800_000*1_800_000
    val finish=start+86_400_000
    val groups=remember(state.sourceGroups,state.channelsBySourceGroup,state.source,programs,now/30_000,finish){
        state.sourceGroups.filter{group->
            val inGroup=state.channelsBySourceGroup[state.source to group].orEmpty()
            inGroup.isNotEmpty()&&(group!="With EPG"||inGroup.any{channel->
                programs[channel.id].orEmpty().any{JaLiveTvRules.hasGuide(it.start,it.end,it.title,now,finish)}
            })
        }
    }
    val candidates=remember(state.channelsBySourceGroup,state.source,state.view){
        state.channelsBySourceGroup[state.source to state.view].orEmpty()
    }
    val epgTimeBucket=if(state.view=="With EPG")now/30_000 else 0L
    val available=remember(candidates,state.query,state.view,state.source,state.adultOnlyUnlocked,state.favorites,state.guide,epgTimeBucket){candidates.filter{channel->
        (channel.id!=JA_WEATHER_CHANNEL||state.view=="All Favorites")
            &&(state.view!="All Favorites"||channel.id in state.favorites)
            &&(!channel.categories.contains("ntv-adult-only")||state.source=="NTV"&&state.view=="Adult Only"&&state.adultOnlyUnlocked)
            &&(state.view!="With EPG"||programs[channel.id].orEmpty().any{JaLiveTvRules.hasGuide(it.start,it.end,it.title,now,finish)})
            &&channel.name.contains(state.query,true)}}
    val channelMap=remember(available){available.associateBy{it.id}}
    val availableIds=remember(available){available.map{it.id}}
    val visibleIds=remember(state.view,availableIds,state.movingId,state.draftOrder){state.order(state.view,availableIds)}
    val visible=remember(channelMap,visibleIds){visibleIds.mapNotNull{channelMap[it]}}
    fun focusPlaying(){
        val id=state.playingChannelId?:state.previewChannel?.id?:JA_WEATHER_CHANNEL
        if(id !in channelMap){state.query="";state.view=if(id==JA_WEATHER_CHANNEL)"All Favorites" else "United States"}
        state.returnChannel=id
    }
    var entryFocusRestored by remember { mutableStateOf(false) }
    LaunchedEffect(state.catalogReady,state.channels,state.fullscreen){
        if(!state.catalogReady||state.fullscreen)return@LaunchedEffect
        val restoreEntryFocus = !entryFocusRestored
        if(!restoreEntryFocus && state.channels.any { it.id == state.previewChannel?.id }) return@LaunchedEffect
        fun allowedToRestore(channel:JaLiveChannel)=!channel.categories.contains("ntv-adult-only")&&
            (state.source!="Favorites"||channel.id in state.favorites)
        val target=state.channels.firstOrNull{it.id==state.lastChannelId&&allowedToRestore(it)}
            ?:state.favoriteOrder.firstNotNullOfOrNull{id->state.channels.firstOrNull{it.id==id&&allowedToRestore(it)}}
            ?:state.channels.firstOrNull{allowedToRestore(it)}
            ?:return@LaunchedEffect
        withFrameNanos {}
        state.tune(target,false)
        if(!restoreEntryFocus) return@LaunchedEffect
        entryFocusRestored=true
        val targetGroup=if(target.id==JA_WEATHER_CHANNEL)"All Favorites"
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
        if(state.source=="NTV"&&group=="Adult Only"&&!state.adultOnlyUnlocked){
            if(pinDialog==null)pinDialog=JaNtvPinDialog.UNLOCK_ADULT
            return
        }
        if(group!="Adult Only")state.lockAdultOnly()
        if(state.view!=group){state.finishMove(false);state.view=group;state.returnChannel=null}
    }
    fun selectSource(source:String){
        if(source=="NTV"&&!pinStore.hasPin()){
            if(pinDialog==null)pinDialog=JaNtvPinDialog.CREATE
            return
        }
        if(state.source==source)return
        state.lockAdultOnly()
        state.finishMove(false);state.source=source;state.query="";state.returnChannel=null
        scope.launch{withFrameNanos{};withFrameNanos{};runCatching{state.groupFocus.requestFocus()}}
    }
    fun focusGroups(first:Boolean=false){
        state.sidebarOpen=true
        if(first)selectGroup(groups.firstOrNull()?:"Favorites")
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
    fun play(channel:JaLiveChannel){
        if(channel.categories.contains("ntv-adult-only")&&!(state.source=="NTV"&&state.view=="Adult Only"&&state.adultOnlyUnlocked)){
            if(state.source=="NTV")selectGroup("Adult Only")
            return
        }
        state.tune(channel, channel.embeddedPlayerUrl != null || state.previewChannel?.id == channel.id)
    }
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
    BackHandler(!state.fullscreen&&menuChannel==null&&!searchOpen&&state.movingId==null&&(groupsFocused||sourcesFocused||guideFocused)){
        if(state.sidebarOpen&&(groupsFocused||sourcesFocused)){state.sidebarOpen=false;onMainMenu()} else focusGroups()
    }
    LaunchedEffect(state.source,groups){
        if(state.catalogReady&&state.view !in groups){
            val next=when{
                groups.contains("United States")&&state.source=="iptv-org.com"->"United States"
                groups.contains("DaddyLive USA")->"DaddyLive USA"
                else->groups.firstOrNull() ?: "Favorites"
            }
            state.view=next;state.returnChannel=null
        }
    }
    BackHandler(state.movingGroupId!=null){state.finishGroupMove(false)}
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
        if(state.movingGroupId!=null)when(key.keyCode){
            KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN->{if(key.action==KeyEvent.ACTION_DOWN)state.moveGroup(if(key.keyCode==KeyEvent.KEYCODE_DPAD_UP)-1 else 1);true}
            KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_NUMPAD_ENTER->{if(key.action==KeyEvent.ACTION_UP)state.finishGroupMove(true);true}
            KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT->true
            else->false
        } else if(state.movingId==null)false else when(key.keyCode){
            KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN->{if(key.action==KeyEvent.ACTION_DOWN)state.moveChannel(if(key.keyCode==KeyEvent.KEYCODE_DPAD_UP)-1 else 1);true}
            KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_NUMPAD_ENTER->{
                if(key.action==KeyEvent.ACTION_UP){if(state.suppressPickUpRelease)state.suppressPickUpRelease=false else state.finishMove(true)};true
            }
            KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT->true
            else->false
        }
    }){
        val sourceWidth=if(state.sidebarOpen)minOf(126.dp,maxWidth*0.14f) else 0.dp
        val sidebarWidth=if(state.sidebarOpen)minOf(170.dp,maxWidth*0.20f) else 0.dp
        val guideWidth=(maxWidth-sourceWidth-sidebarWidth-if(state.sidebarOpen)16.dp else 0.dp).coerceAtLeast(100.dp)
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
            if(state.sidebarOpen)Column(Modifier.width(sourceWidth).fillMaxHeight().background(Color(0xFF17212D),RoundedCornerShape(6.dp))
                .onFocusChanged{sourcesFocused=it.hasFocus}.focusGroup()
                .onPreviewKeyEvent{event->
                    val key=event.nativeKeyEvent
                    if(key.action!=KeyEvent.ACTION_DOWN)false else when(key.keyCode){
                        KeyEvent.KEYCODE_DPAD_UP->{if(focusedSource==0)focusJaButton();focusedSource==0}
                        KeyEvent.KEYCODE_DPAD_LEFT->{focusJaButton();true}
                        else->false
                    }
                }){
                Text("Source",Modifier.padding(start=10.dp,top=12.dp,bottom=8.dp),color=ColorGold,fontSize=15.sp)
                LazyColumn(state=state.sourceList,modifier=Modifier.fillMaxWidth().weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp),contentPadding=PaddingValues(horizontal=5.dp,vertical=4.dp)){
                    items(JaLiveTvRules.sources(),key={it},contentType={"iptv-source"}){source->
                        TvFocusButton(onClick={selectSource(source)},modifier=Modifier.fillMaxWidth()
                            .then(if(state.source==source)Modifier.focusRequester(state.sourceFocus) else Modifier)
                            .onFocusChanged{if(it.isFocused)focusedSource=JaLiveTvRules.sources().indexOf(source)},
                            colors=ButtonDefaults.buttonColors(containerColor=if(state.source==source)ColorGold else Color(0xFF263747),
                                contentColor=if(state.source==source)Color.Black else Color.White)){
                            Text(source,fontSize=11.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if(state.sidebarOpen)Column(Modifier.width(sidebarWidth).fillMaxHeight().background(Color(0xFF1B2735),RoundedCornerShape(6.dp))
                .onFocusChanged{groupsFocused=it.hasFocus}.focusGroup()
                .onPreviewKeyEvent{event->
                    val key=event.nativeKeyEvent
                    if(key.keyCode==KeyEvent.KEYCODE_DPAD_LEFT){
                        if(key.action==KeyEvent.ACTION_DOWN)scope.launch{state.sourceList.scrollToItem(JaLiveTvRules.sources().indexOf(state.source).coerceAtLeast(0));withFrameNanos{};runCatching{state.sourceFocus.requestFocus()}}
                        true
                    }else
                    if(key.keyCode==KeyEvent.KEYCODE_DPAD_UP&&!searchFocused&&focusedGroup==0){
                        // The Groups header owns search; Up from the first group reaches its icon.
                        if(key.action==KeyEvent.ACTION_DOWN)runCatching{searchFocus.requestFocus()}
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
                    Text(if(state.movingGroupId==null)"Groups" else "Move ${state.movingGroupId} · ↑/↓ move · OK save · Back cancel",
                        Modifier.weight(1f),color=ColorGold,fontSize=if(state.movingGroupId==null)16.sp else 10.sp,maxLines=2)
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
                        var focused by remember{mutableStateOf(false)}
                        Row(Modifier.fillMaxWidth()
                            .then(if(state.view==group||state.movingGroupId==group)Modifier.focusRequester(state.groupFocus) else Modifier)
                            .jaLiveSelect(onClick={if(state.movingGroupId==null)selectGroup(group)},
                                onMenu={},onFocus={hasFocus->
                                    focused=hasFocus
                                    if(hasFocus){
                                        focusedGroup=groups.indexOf(group);searchFocused=false
                                        // Protected group selection is an explicit OK action.
                                        if(state.movingGroupId==null&&group!="Adult Only")selectGroup(group)
                                    }
                                })
                            .background(if(state.movingGroupId==group)Color(0xFF75551E) else if(state.view==group)ColorGold else if(focused)Color(0xFF394B5E) else Color(0xFF263747),RoundedCornerShape(4.dp))
                            .heightIn(min=40.dp).padding(horizontal=12.dp,vertical=9.dp),verticalAlignment=Alignment.CenterVertically){
                            Text(if(group=="Adult Only"&&!state.adultOnlyUnlocked)"Adult Only · PIN" else group,
                                color=if(state.view==group)Color.Black else Color.White,
                                fontSize=12.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
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
                        else if (channel.embeddedPlayerUrl != null) {
                            if (state.fullscreen) key(channel.id) {
                                JaLiveEmbeddedPlayback(
                                    channel.embeddedPlayerUrl, channel.name,
                                    onClose = {
                                        state.fullscreen = false
                                        state.returnChannel = channel.id
                                    },
                                    onPlaying = { state.playbackStarted(channel) },
                                    onAttached = { state.playerAttached = it },
                                    fallbackUrls = channel.embeddedPlayerFallbackUrls,
                                    matchTvFrameRate=matchTvFrameRate,matchTvResolution=matchTvResolution,
                                    matchStreamQuality=matchStreamQuality,
                                    playerTelemetryEnabled=playerTelemetryEnabled,
                                    hlsRequestInspectionEnabled=hlsRequestInspectionEnabled
                                )
                            } else Text("Select the channel to watch", Modifier.align(Alignment.Center),
                                color = Color.LightGray, fontSize = 12.sp)
                        }
                        else key(channel.id){JaLiveTvPlayback(channel,state.repository,state.fullscreen,
                            matchTvFrameRate,matchTvResolution,matchStreamQuality,
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
    pinDialog?.let{mode->
        JaNtvPinPrompt(mode,pinStore,
            onDismiss={pinDialog=null},
            onCreated={pinDialog=null;selectSource("NTV")},
            onUnlocked={pinDialog=null;state.adultOnlyUnlocked=true;selectGroup("Adult Only")})
    }
    menuChannel?.let{channel->
        JaLiveChannelMenu(channel,state.favorites.contains(channel.id),menuGuard,
            canReorder=channel.id in visibleIds,canReorderGroups=groups.isNotEmpty(),canRefresh=!state.loading&&!state.guideLoading,
            onClose={closeMenu()},onWatch={menuChannel=null;play(channel)},
            onFavorite={state.favorite(channel.id);closeMenu()},
            onReorder={menuChannel=null;state.beginMove(channel.id,visibleIds,false)},
            onReorderGroups={
                menuChannel=null
                scope.launch{withFrameNanos{};state.beginGroupMove(state.view,groups);withFrameNanos{};runCatching{state.groupFocus.requestFocus()}}
            },
            onRefresh={closeMenu();state.refresh(true)})
    }
}

@Composable
private fun JaNtvPinPrompt(
    mode:JaNtvPinDialog,
    store:JaIptvPinStore,
    onDismiss:()->Unit,
    onCreated:()->Unit,
    onUnlocked:()->Unit
) {
    val scope=rememberCoroutineScope()
    val inputFocus=remember{FocusRequester()}
    var pin by remember(mode){mutableStateOf("")}
    var confirmation by remember(mode){mutableStateOf("")}
    var error by remember(mode){mutableStateOf("")}
    var working by remember(mode){mutableStateOf(false)}
    val creating=mode==JaNtvPinDialog.CREATE
    val valid=pin.length==6&&(!creating||confirmation.length==6)
    LaunchedEffect(mode){withFrameNanos{};runCatching{inputFocus.requestFocus()}}
    AlertDialog(
        onDismissRequest=onDismiss,
        properties=DialogProperties(securePolicy=androidx.compose.ui.window.SecureFlagPolicy.SecureOn),
        title={Text(if(creating)"Create NTV PIN" else "Adult Only")},
        text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text(if(creating)"Create a six-digit PIN to enable NTV." else "Enter your six-digit PIN to open Adult Only channels.")
            OutlinedTextField(
                value=pin,
                onValueChange={value->pin=value.filter(Char::isDigit).take(6);error=""},
                modifier=Modifier.fillMaxWidth().focusRequester(inputFocus),
                label={Text(if(creating)"New PIN" else "6-digit PIN")},
                singleLine=true,
                visualTransformation=PasswordVisualTransformation(),
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword)
            )
            if(creating)OutlinedTextField(
                value=confirmation,
                onValueChange={value->confirmation=value.filter(Char::isDigit).take(6);error=""},
                modifier=Modifier.fillMaxWidth(),
                label={Text("Confirm PIN")},
                singleLine=true,
                visualTransformation=PasswordVisualTransformation(),
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword)
            )
            if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
        }},
        confirmButton={TextButton(enabled=valid&&!working,onClick={
            if(creating&&pin!=confirmation){error="PIN entries do not match.";return@TextButton}
            working=true
            val submittedPin=pin
            scope.launch{
                val result=runCatching{withContext(Dispatchers.Default){
                    if(creating){store.save(submittedPin);true}else store.verify(submittedPin)
                }}
                working=false
                result.onSuccess{accepted->
                    if(!accepted)error="That PIN is incorrect. Try again."
                    else if(creating)onCreated() else onUnlocked()
                }.onFailure{error="Could not save the PIN. Try again."}
            }
        }){Text(if(working)"Checking…" else if(creating)"Create PIN" else "Unlock")}},
        dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}}
    )
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
    canReorder:Boolean,canReorderGroups:Boolean,canRefresh:Boolean,onClose:()->Unit,onWatch:()->Unit,onFavorite:()->Unit,
    onReorder:()->Unit,onReorderGroups:()->Unit,onRefresh:()->Unit){
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
                Text("Group / Channel Options",color=ColorGold,fontSize=20.sp)
                Text(channel.name,color=Color.White,fontSize=16.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                val colors=ButtonDefaults.buttonColors(containerColor=Color(0xFF2A3A4B),contentColor=Color.White)
                TvFocusButton(onClick=onWatch,modifier=Modifier.fillMaxWidth().focusRequester(first),colors=colors){Text("Watch fullscreen")}
                TvFocusButton(onClick=onFavorite,modifier=Modifier.fillMaxWidth(),colors=colors){Text(if(favorite)"Remove from Favorites" else "Add to Favorites")}
                TvFocusButton(onClick=onReorder,enabled=canReorder,modifier=Modifier.fillMaxWidth(),colors=colors){Text("Reorder channels")}
                TvFocusButton(onClick=onReorderGroups,enabled=canReorderGroups,modifier=Modifier.fillMaxWidth(),colors=colors){Text("Reorder groups")}
                TvFocusButton(onClick=onRefresh,enabled=canRefresh,modifier=Modifier.fillMaxWidth(),colors=colors){Text("Refresh channels and guide")}
                TvFocusButton(onClick=onClose,modifier=Modifier.fillMaxWidth(),colors=colors){Text("Close")}
            }
        }
    }
}

@Composable
private fun JaLiveEmbeddedPlayback(
    url: String, name: String, onClose: () -> Unit, onPlaying: () -> Unit, onAttached: (Boolean) -> Unit,
    fallbackUrls: List<String> = emptyList(),matchTvFrameRate:Boolean=false,matchTvResolution:Boolean=false,
    matchStreamQuality:Boolean=false,playerTelemetryEnabled:Boolean=BuildConfig.DEBUG,
    hlsRequestInspectionEnabled:Boolean=BuildConfig.DEBUG
) {
    var frameMatcher by remember(url,playerTelemetryEnabled,matchTvFrameRate,matchTvResolution){
        mutableStateOf<JaTvFrameRateMatcher?>(null)
    }
    val capturedCandidates = remember(url) { mutableStateListOf<JaMediaSourceCandidate>() }
    var nativeCandidate by remember(url) { mutableStateOf<JaMediaSourceCandidate?>(null) }
    var nativeHandoffCandidates by remember(url) { mutableStateOf<List<JaMediaSourceCandidate>>(emptyList()) }
    var detailsCandidate by remember(url) { mutableStateOf<JaMediaSourceCandidate?>(null) }
    val effectiveFrameRateMatching=playerTelemetryEnabled&&matchTvFrameRate
    val qualityMatching=playerTelemetryEnabled&&matchStreamQuality&&matchTvFrameRate&&matchTvResolution
    var fallbackIndex by remember(url) { mutableIntStateOf(0) }
    val activeUrl=(listOf(url)+fallbackUrls).getOrElse(fallbackIndex){url}
    val attached by rememberUpdatedState(onAttached)
    DisposableEffect(Unit) {
        attached(true)
        onDispose { attached(false) }
    }
    var failed by remember(activeUrl) { mutableStateOf(false) }
    JaLivePresentation(true, onClose) {
        JaLiveFrameMatcherHost(effectiveFrameRateMatching,matchTvResolution){frameMatcher=it}
        val candidatePlayer = nativeCandidate
        if (candidatePlayer != null) {
            JaNativeCandidatePlayer(candidatePlayer, nativeHandoffCandidates, onBack = { nativeCandidate = null })
        } else if (failed) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Channel unavailable", color = Color.White)
                    TvFocusButton(onClick = { failed = false }) { Text("Retry") }
                    TvFocusButton(onClick = onClose) { Text("Back to guide") }
                }
            }
        } else GeckoExperimentalPlayer(url = activeUrl, diagnosticsLabel = name,
            enablePlaybackTelemetry = playerTelemetryEnabled,
            enableMediaSourceDiagnostics = BuildConfig.DEBUG && hlsRequestInspectionEnabled,
            onMediaSourceCandidate = { candidate ->
                if (BuildConfig.DEBUG && hlsRequestInspectionEnabled && candidate.kind in setOf("HLS", "DASH", "MEDIA", "SEGMENT", "KEY", "IFRAME")) {
                    val candidateHost = Uri.parse(candidate.url).host
                    // Keep exact manifests and AES key URLs, but retain only one
                    // representative media request per host/frame to bound memory.
                    val identity = when (candidate.kind) {
                        "HLS", "DASH", "KEY" -> "${candidate.frameId}|${candidate.kind}|${candidate.url}"
                        else -> "${candidate.frameId}|${candidate.kind}|$candidateHost"
                    }
                    val existing = capturedCandidates.indexOfFirst {
                        val oldIdentity = when (it.kind) {
                            "HLS", "DASH", "KEY" -> "${it.frameId}|${it.kind}|${it.url}"
                            else -> "${it.frameId}|${it.kind}|${Uri.parse(it.url).host}"
                        }
                        oldIdentity == identity
                    }
                    if (existing >= 0) {
                        if (candidate.requestHeaders.size > capturedCandidates[existing].requestHeaders.size)
                            capturedCandidates[existing] = candidate
                    } else if (capturedCandidates.size < 24) {
                        capturedCandidates.add(candidate)
                    }
                }
            },
            onMediaStarted = onPlaying, frameRateMatcher=frameMatcher,matchStreamQuality=qualityMatching,onFatalError = {
                if(fallbackIndex<fallbackUrls.size)fallbackIndex++ else failed=true
            })
        val readyCandidates = capturedCandidates.filter { it.requestHeaders.isNotEmpty() }
        if (BuildConfig.DEBUG && hlsRequestInspectionEnabled && nativeCandidate == null && capturedCandidates.isNotEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
                Column(Modifier.padding(12.dp)
                    .background(Color(0xDD111820), RoundedCornerShape(8.dp)).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Captured ${readyCandidates.size} video request(s)", color = Color.White, fontSize = 12.sp)
                    readyCandidates.forEachIndexed { index, candidate ->
                        val host = Uri.parse(candidate.url).host ?: "stream host"
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TvFocusButton(onClick = { detailsCandidate = candidate }) {
                                Text("Headers ${index + 1} · ${candidate.kind} · $host · ${candidate.requestHeaders.size}", fontSize = 11.sp)
                            }
                            if (candidate.kind in setOf("HLS", "DASH")) {
                                TvFocusButton(onClick = {
                                    nativeHandoffCandidates = capturedCandidates.toList()
                                    nativeCandidate = candidate
                                }) {
                                    Text("Test in Exo", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
        detailsCandidate?.let { candidate -> JaMediaRequestDetailsDialog(candidate) { detailsCandidate = null } }
    }
}

@Composable
private data class JaNativeHttpResult(
    val responseCode: Int,
    val responseUri: String,
    val responseHeaders: Map<String, List<String>>
)

@OptIn(androidx.media3.common.util.UnstableApi::class)
private class JaObservedHttpDataSource(
    private val upstream: HttpDataSource,
    private val onResponse: (DataSpec, JaNativeHttpResult) -> Unit
) : HttpDataSource by upstream {
    override fun open(dataSpec: DataSpec): Long {
        return try {
            val length = upstream.open(dataSpec)
            onResponse(dataSpec, JaNativeHttpResult(
                responseCode = upstream.responseCode,
                responseUri = upstream.uri?.toString().orEmpty(),
                responseHeaders = upstream.responseHeaders
            ))
            length
        } catch (error: java.io.IOException) {
            val responseCodeError = error as? HttpDataSource.InvalidResponseCodeException
            val responseCode = responseCodeError?.responseCode
                ?: runCatching { upstream.responseCode }.getOrDefault(-1)
            if (responseCode >= 0) {
                onResponse(dataSpec, JaNativeHttpResult(
                    responseCode = responseCode,
                    responseUri = upstream.uri?.toString().orEmpty(),
                    responseHeaders = responseCodeError?.headerFields ?: upstream.responseHeaders
                ))
            }
            throw error
        }
    }
}

@OptIn(androidx.media3.common.util.UnstableApi::class)
private class JaObservedHttpDataSourceFactory(
    private val upstream: HttpDataSource.Factory,
    private val onResponse: (DataSpec, JaNativeHttpResult) -> Unit
) : HttpDataSource.Factory by upstream {
    override fun createDataSource(): HttpDataSource =
        JaObservedHttpDataSource(upstream.createDataSource(), onResponse)
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun JaNativeCandidatePlayer(
    candidate: JaMediaSourceCandidate,
    capturedCandidates: List<JaMediaSourceCandidate>,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var status by remember(candidate.url) { mutableStateOf("Preparing native ${candidate.kind} test…") }
    val exoLoads = remember(candidate.url) { mutableStateListOf<JaMediaSourceCandidate>() }
    var detailsCandidate by remember(candidate.url) { mutableStateOf<JaMediaSourceCandidate?>(null) }
    val player = remember(candidate.url, candidate.referer, candidate.userAgent, candidate.requestHeaders, capturedCandidates) {
        val originHost = Uri.parse(candidate.url).host.orEmpty()
        val generatedHeaders = setOf(
            "host", "connection", "keep-alive", "content-length", "transfer-encoding", "upgrade",
            "proxy-connection", "proxy-authenticate", "te", "trailer", "accept-encoding", "range",
            "if-range", "if-none-match", "if-modified-since"
        )
        fun safeCapturedHeaders(source: Map<String, String>): Map<String, String> = source.filter { (name, value) ->
            name.isNotBlank() && !name.startsWith(":") && name.lowercase() !in generatedHeaders && value.isNotBlank()
        }
        val exactUrlHeaders = linkedMapOf<String, Map<String, String>>()
        val hostHeaders = linkedMapOf<String, Map<String, String>>()
        capturedCandidates
            .filter {
                it.frameId == candidate.frameId && it.requestHeaders.isNotEmpty() &&
                    (candidate.pageUrl.isBlank() || it.pageUrl == candidate.pageUrl)
            }
            .forEach { observed ->
                val observedUrl = Uri.parse(observed.url)
                val observedHost = observedUrl.host?.lowercase() ?: return@forEach
                val observedHeaders = safeCapturedHeaders(observed.requestHeaders)
                exactUrlHeaders[observed.url] = observedHeaders
                val previous = hostHeaders[observedHost]
                // Prefer the richest request actually sent to this host. This
                // keeps cookies and custom tokens bound to their observed host.
                if (previous == null || observedHeaders.size > previous.size)
                    hostHeaders[observedHost] = observedHeaders
            }
        if (candidate.url !in exactUrlHeaders)
            exactUrlHeaders[candidate.url] = safeCapturedHeaders(candidate.requestHeaders)
        val safeManifestHeaders = safeCapturedHeaders(candidate.requestHeaders)
        val nonSensitiveFallback = safeManifestHeaders.filterKeys {
            it.equals("User-Agent", true) || it.equals("Accept", true) || it.equals("Accept-Language", true)
        }
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(candidate.userAgent.takeIf { it.isNotBlank() } ?: "JA/2.8.8")
        val httpResults = java.util.concurrent.ConcurrentHashMap<String, JaNativeHttpResult>()
        val observedHttp = JaObservedHttpDataSourceFactory(http) { dataSpec, result ->
                httpResults[dataSpec.uri.toString()] = result
                while (httpResults.size > 32) httpResults.keys.firstOrNull()?.let(httpResults::remove) ?: break
        }
        val upstream = DefaultDataSource.Factory(context, observedHttp)
        val loggedRequestContexts = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val requestScopedFactory = ResolvingDataSource.Factory(upstream) { dataSpec ->
            val requestUrl = dataSpec.uri.toString()
            val normalizedHost = dataSpec.uri.host?.lowercase().orEmpty()
            val safeHeaders = exactUrlHeaders[requestUrl]
                ?: hostHeaders[normalizedHost]
                ?: nonSensitiveFallback
            val requestHost = normalizedHost.ifBlank { "unknown" }
            val headerNames = safeHeaders.keys.sorted().joinToString(",").ifBlank { "none" }
            if (loggedRequestContexts.add("$requestHost|$headerNames")) {
                Log.i("JA_MEDIA_DIAG", "NATIVE_REQUEST_CONTEXT kind=${candidate.kind} " +
                    "host=$requestHost headerNames=$headerNames")
            }
            dataSpec.buildUpon().setHttpRequestHeaders(safeHeaders).build()
        }
        Log.i("JA_MEDIA_DIAG", "NATIVE_TEST_START kind=${candidate.kind} host=$originHost " +
            "headerNames=${headers.keys.sorted().joinToString(",").ifBlank { "none" }}")
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(requestScopedFactory))
            .build().apply {
                addAnalyticsListener(object : AnalyticsListener {
                    private fun requestKind(dataType: Int): String = when (dataType) {
                        C.DATA_TYPE_MANIFEST -> "EXO_MANIFEST"
                        C.DATA_TYPE_MEDIA -> "EXO_MEDIA"
                        else -> "EXO_LOAD_$dataType"
                    }

                    private fun upsert(record: JaMediaSourceCandidate) {
                        val index = exoLoads.indexOfFirst { it.url == record.url && it.kind == record.kind }
                        if (index >= 0) exoLoads[index] = record else {
                            if (exoLoads.size >= 32) exoLoads.removeAt(0)
                            exoLoads.add(record)
                        }
                    }

                    override fun onLoadStarted(
                        eventTime: AnalyticsListener.EventTime,
                        loadEventInfo: LoadEventInfo,
                        mediaLoadData: MediaLoadData
                    ) {
                        val dataSpec = loadEventInfo.dataSpec
                        if (dataSpec.uri.scheme != "http" && dataSpec.uri.scheme != "https") return
                        val requestHeaders = dataSpec.httpRequestHeaders
                        upsert(JaMediaSourceCandidate(
                            url = dataSpec.uri.toString(),
                            kind = requestKind(mediaLoadData.dataType),
                            referer = requestHeaders.entries.firstOrNull { it.key.equals("Referer", true) }?.value.orEmpty(),
                            userAgent = requestHeaders.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value.orEmpty(),
                            requestHeaders = requestHeaders
                        ))
                    }

                    override fun onLoadCompleted(
                        eventTime: AnalyticsListener.EventTime,
                        loadEventInfo: LoadEventInfo,
                        mediaLoadData: MediaLoadData
                    ) {
                        val dataSpec = loadEventInfo.dataSpec
                        val kind = requestKind(mediaLoadData.dataType)
                        val httpResult = httpResults.remove(dataSpec.uri.toString())
                        val responseHeaderSource = loadEventInfo.responseHeaders.ifEmpty { httpResult?.responseHeaders.orEmpty() }
                        val responseHeaders = responseHeaderSource.mapValues { (_, values) -> values.joinToString("; ") }
                        val contentType = responseHeaders.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value.orEmpty()
                        val index = exoLoads.indexOfFirst { it.url == dataSpec.uri.toString() && it.kind == kind }
                        val previous = exoLoads.getOrNull(index)
                        val completed = (previous ?: JaMediaSourceCandidate(
                            url = dataSpec.uri.toString(), kind = kind, referer = "", userAgent = "",
                            requestHeaders = dataSpec.httpRequestHeaders
                        )).copy(
                            responseUrl = httpResult?.responseUri?.ifBlank { null } ?: loadEventInfo.uri.toString(),
                            responseHeaders = responseHeaders,
                            responseStatus = httpResult?.responseCode,
                            bytesLoaded = loadEventInfo.bytesLoaded,
                            loadDurationMs = loadEventInfo.loadDurationMs
                        )
                        upsert(completed)
                        Log.i("JA_MEDIA_DIAG", "NATIVE_LOAD_COMPLETE kind=$kind " +
                            "host=${dataSpec.uri.host ?: "unknown"} finalHost=${loadEventInfo.uri.host ?: "unknown"} " +
                            "mime=${contentType.substringBefore(';')} bytes=${loadEventInfo.bytesLoaded} " +
                            "durationMs=${loadEventInfo.loadDurationMs}")
                    }

                    override fun onLoadError(
                        eventTime: AnalyticsListener.EventTime,
                        loadEventInfo: LoadEventInfo,
                        mediaLoadData: MediaLoadData,
                        error: java.io.IOException,
                        wasCanceled: Boolean
                    ) {
                        val dataSpec = loadEventInfo.dataSpec
                        val kind = requestKind(mediaLoadData.dataType)
                        val httpError = error as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
                        val httpResult = httpResults.remove(dataSpec.uri.toString())
                        val index = exoLoads.indexOfFirst { it.url == dataSpec.uri.toString() && it.kind == kind }
                        val previous = exoLoads.getOrNull(index)
                        upsert((previous ?: JaMediaSourceCandidate(
                            url = dataSpec.uri.toString(), kind = kind, referer = "", userAgent = "",
                            requestHeaders = dataSpec.httpRequestHeaders
                        )).copy(
                            responseUrl = httpResult?.responseUri?.ifBlank { null } ?: loadEventInfo.uri.toString(),
                            responseHeaders = (httpError?.headerFields ?: httpResult?.responseHeaders.orEmpty()).mapValues { (_, values) -> values.joinToString("; ") },
                            responseStatus = httpError?.responseCode ?: httpResult?.responseCode,
                            bytesLoaded = loadEventInfo.bytesLoaded,
                            loadDurationMs = loadEventInfo.loadDurationMs,
                            loadError = "${httpError?.let { "HTTP ${it.responseCode}: " } ?: ""}${error.javaClass.simpleName}: ${error.message.orEmpty()}"
                        ))
                        Log.w("JA_MEDIA_DIAG", "NATIVE_LOAD_ERROR kind=$kind " +
                            "host=${dataSpec.uri.host ?: "unknown"} finalHost=${loadEventInfo.uri.host ?: "unknown"} " +
                            "http=${httpError?.responseCode ?: 0} canceled=$wasCanceled error=${error.javaClass.simpleName}")
                    }
                })
                setMediaItem(MediaItem.fromUri(candidate.url))
                prepare()
                playWhenReady = true
            }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                status = if (isPlaying) "Native playback started" else "Native player is buffering or paused"
            }
            override fun onPlayerError(error: PlaybackException) {
                var cause: Throwable? = error
                var httpCode: Int? = null
                while (cause != null && httpCode == null) {
                    if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)
                        httpCode = cause.responseCode
                    cause = cause.cause
                }
                status = if (httpCode != null) "Native test failed · HTTP $httpCode"
                    else "Native test failed · ${error.errorCodeName}"
                Log.i("JA_MEDIA_DIAG", "NATIVE_TEST_FAILED kind=${candidate.kind} " +
                    "host=${Uri.parse(candidate.url).host ?: "unknown"} http=${httpCode ?: 0} code=${error.errorCodeName}")
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
            Log.i("JA_MEDIA_DIAG", "NATIVE_TEST_RELEASE host=${Uri.parse(candidate.url).host ?: "unknown"}")
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { PlayerView(it).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            this.player = player
            useController = true
            keepScreenOn = true
        } }, update = { it.player = player }, modifier = Modifier.fillMaxSize(),
            onReset = null, onRelease = { it.player = null; it.keepScreenOn = false })
        Column(Modifier.align(Alignment.TopStart).padding(14.dp)
            .background(Color(0xDD111820), RoundedCornerShape(8.dp)).padding(10.dp)) {
            Text(status, color = Color.White, fontSize = 12.sp)
            Text("Source host: ${Uri.parse(candidate.url).host ?: "unknown"}", color = Color.LightGray, fontSize = 11.sp)
            if (BuildConfig.DEBUG && exoLoads.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("Recent native loads", color = Color.White, fontSize = 11.sp)
                exoLoads.takeLast(6).forEach { record ->
                    val host = Uri.parse(record.responseUrl.ifBlank { record.url }).host ?: "unknown"
                    val mime = record.responseHeaders.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value
                        ?.substringBefore(';')?.ifBlank { "mime unknown" } ?: "mime unknown"
                    TvFocusButton(onClick = { detailsCandidate = record }) {
                        Text("${record.kind} · $host · $mime · ${record.bytesLoaded ?: 0} B" +
                            if (record.loadError.isNotBlank()) " · ERROR" else "", fontSize = 10.sp)
                    }
                }
            }
            TvFocusButton(onClick = onBack) { Text("Back to web player") }
        }
    }
    detailsCandidate?.let { JaMediaRequestDetailsDialog(it) { detailsCandidate = null } }
}

/** A single session owns playback; fullscreen only changes the view hosting that session. */
@Composable
private fun JaLiveTvPlayback(channel:JaLiveChannel,repository:JaLiveTvRepository,fullscreen:Boolean,
    matchTvFrameRate:Boolean,matchTvResolution:Boolean,matchStreamQuality:Boolean,
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
        matchTvFrameRate=matchTvFrameRate,matchTvResolution=matchTvResolution,matchStreamQuality=matchStreamQuality,
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

@Composable
private fun JaLiveFrameMatcherHost(enabled:Boolean,preferSourceResolution:Boolean,onMatcher:(JaTvFrameRateMatcher?)->Unit){
    val context=LocalContext.current
    val activity=context as? Activity
    val view=LocalView.current
    val window=(view.parent as? DialogWindowProvider)?.window?:activity?.window
    val callback by rememberUpdatedState(onMatcher)
    val matcher=remember(activity,window,enabled,preferSourceResolution){
        if(enabled&&activity!=null&&window!=null)JaTvFrameRateMatcher(activity,preferSourceResolution,window) else null
    }
    SideEffect{callback(matcher)}
    DisposableEffect(matcher){onDispose{matcher?.close();callback(null)}}
}

private data class JaLiveReconnect(val target:Int,val reason:String,val delayMs:Long)

internal enum class JaIptvBuffer(
    val label: String, val minMs: Int = 0, val maxMs: Int = 0,
    val startupMs: Int = 0, val rebufferMs: Int = 0
) {
    SMALL("Small", 5_000, 15_000, 750, 1_500),
    DEFAULT("Default"),
    LARGE("Large", 45_000, 75_000, 1_500, 3_000),
    EXTRA_LARGE("Extra large", 60_000, 100_000, 2_000, 5_000)
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun JaLiveTvSession(channel:JaLiveChannel,fullscreen:Boolean,onRetry:()->Unit,onClose:()->Unit,
    matchTvFrameRate:Boolean,matchTvResolution:Boolean,matchStreamQuality:Boolean,
    onFullscreen:()->Unit,onPlaying:(JaLiveChannel)->Unit,onAttached:(Boolean)->Unit) {
    val context=LocalContext.current
    val configuredBuffer = remember(context) { PlayerControlSettings(context).iptvBuffer }
    val buffer = remember(context, configuredBuffer) {
        val effective = if (
            configuredBuffer == JaIptvBuffer.DEFAULT && JaMemoryPolicy.isConstrainedDevice()
        ) JaIptvBuffer.SMALL else configuredBuffer
        if (effective != configuredBuffer) {
            Log.i(
                "JA_MEMORY",
                "IPTV_BUFFER_PROFILE configured=${configuredBuffer.name} effective=${effective.name} " +
                    "reason=constrained_device maxBufferMs=${effective.maxMs}"
            )
        }
        effective
    }
    var frameMatcher by remember(channel){mutableStateOf<JaTvFrameRateMatcher?>(null)}
    val qualityMatching=fullscreen&&matchStreamQuality&&matchTvFrameRate&&matchTvResolution
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
    val trackSelector=remember(channel,source,generation){DefaultTrackSelector(context)}
    var decoderCounters by remember(channel,source,generation){mutableStateOf<DecoderCounters?>(null)}
    var videoWidth by remember(channel,source,generation){mutableIntStateOf(0)}
    var videoHeight by remember(channel,source,generation){mutableIntStateOf(0)}
    val analyticsListener=remember(channel,source,generation){object:AnalyticsListener{
        override fun onVideoEnabled(eventTime:AnalyticsListener.EventTime,counters:DecoderCounters){decoderCounters=counters}
        override fun onVideoDisabled(eventTime:AnalyticsListener.EventTime,counters:DecoderCounters){
            if(decoderCounters===counters)decoderCounters=null
        }
        override fun onVideoSizeChanged(eventTime:AnalyticsListener.EventTime,videoSize:VideoSize){
            videoWidth=videoSize.width;videoHeight=videoSize.height
            Log.i("JA_IPTV_PLAYER","VIDEO_SIZE channel=${channel.id} size=${videoSize.width}x${videoSize.height}")
        }
    }}
    val player=remember(channel,source,generation){
        val headers=channel.headers[channel.urls[source]].orEmpty()
        val http=DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
            .setUserAgent(headers["User-Agent"]?:"JA/2.5.0").setDefaultRequestProperties(headers)
        val builder = ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context,http)))
        if (buffer != JaIptvBuffer.DEFAULT) {
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMsForStreaming(buffer.minMs, buffer.maxMs, buffer.startupMs, buffer.rebufferMs)
                .setPrioritizeTimeOverSizeThresholdsForStreaming(true)
                .build()
            builder.setLoadControl(loadControl)
        }
        builder.build().apply{
        addAnalyticsListener(analyticsListener)
        setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true)
        setHandleAudioBecomingNoisy(true)
        setMediaItem(MediaItem.fromUri(channel.urls[source]));prepare();playWhenReady=wantsPlayback&&resumed
    }}
    LaunchedEffect(player,frameMatcher,qualityMatching,decoderCounters,videoWidth,videoHeight){
        var lastFrames=-1
        var appliedHeight=0
        while(isActive){
            delay(1000)
            val counters=decoderCounters
            if(fullscreen&&player.isPlaying&&counters!=null&&videoWidth>=480&&videoHeight>=270){
                counters.ensureUpdated()
                val frames=counters.renderedOutputBufferCount
                if(frames>0&&frames!=lastFrames){
                    frameMatcher?.observe(frames,player.currentPosition.coerceAtLeast(0L)/1000.0,videoWidth,videoHeight)
                    lastFrames=frames
                }
            }
            val requestedHeight=if(qualityMatching)frameMatcher?.confirmedQualityHeight() else null
            val targetHeight=requestedHeight?:Int.MAX_VALUE
            if(appliedHeight!=targetHeight){
                trackSelector.setParameters(trackSelector.buildUponParameters()
                    .setMaxVideoSize(Int.MAX_VALUE,targetHeight).build())
                appliedHeight=targetHeight
                Log.i("JA_IPTV_PLAYER","QUALITY_LIMIT channel=${channel.id} outputHeight=${requestedHeight?:0} " +
                    "active=${requestedHeight!=null}")
            }
        }
    }
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
        Log.i("JA_LIVE_TV","OPEN channel=${channel.id} source=$source generation=$generation buffer=${buffer.name}")
        onDispose{owner.lifecycle.removeObserver(observer);player.removeListener(listener);player.removeAnalyticsListener(analyticsListener)
            player.release()
            Log.i("JA_LIVE_TV", "RELEASE channel=${channel.id} source=$source generation=$generation")
            reportAttached(false);JaMusicService.setVideoActive(false)}
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
    JaLiveFrameMatcherHost(fullscreen&&matchTvFrameRate,matchTvResolution){frameMatcher=it}
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
