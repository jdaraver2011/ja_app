package com.example.ja

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

internal data class JaArgonCategory(val id:String,val name:String)
internal data class JaArgonChannel(val id:String,val name:String,val logo:String)
internal data class JaArgonChannelPage(val channels:List<JaArgonChannel>,val page:Int,val totalPages:Int,val total:Int)

/** Read-only USA name catalog from ArgonTV. The public catalog API does not expose playback URLs. */
internal class JaArgonCatalogRepository(context:Context) {
    private val cacheDir=File(context.cacheDir,"live-tv").apply{mkdirs()}
    private val prefs=context.getSharedPreferences("ja_argon_catalog_refresh",Context.MODE_PRIVATE)
    private val client=OkHttpClient.Builder().connectTimeout(12,TimeUnit.SECONDS).readTimeout(25,TimeUnit.SECONDS)
        .callTimeout(40,TimeUnit.SECONDS).build()
    private val lock=Mutex()
    private val jsonType="application/json; charset=utf-8".toMediaType()
    private val base="https://argontv.nl/api/v1"

    suspend fun categories(force:Boolean=false):List<JaArgonCategory> = withContext(Dispatchers.IO){lock.withLock{
        val file=File(cacheDir,"argon-usa-categories.json")
        val cached=runCatching{if(file.isFile)parseCategories(file.readText()) else null}.getOrNull()
        val now=System.currentTimeMillis();val zone=ZoneId.systemDefault()
        val date=Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val six=date.atTime(6,0).atZone(zone).toInstant().toEpochMilli()
        val due=cached==null||(now>=six&&file.lastModified()<six)
        val attempted=prefs.getString("attempt_date","")==date.toString()
        val refresh=force||(due&&!attempted)
        if(!refresh)return@withLock cached?:throw IOException("Argon USA catalog is not available")
        if(now>=six)prefs.edit().putString("attempt_date",date.toString()).apply()
        try{
            val loaded=loadUsaCategories()
            if(loaded.isEmpty())throw IOException("ArgonTV returned no USA channel categories")
            file.writeText(categoriesJson(loaded));loaded
        }catch(e:Exception){
            cached?:throw e
        }
    }}

    suspend fun channels(category:String,query:String,page:Int):JaArgonChannelPage=withContext(Dispatchers.IO){
        val doc=post("search-channels-v2",page,JSONObject().put("query",query).put("category_name",category))
        val entries=doc.optJSONArray("entries")?:JSONArray()
        val list=(0 until entries.length()).map{entries.getJSONObject(it)}.mapNotNull{item->
            val display=item.optString("category_display")
            if(!display.startsWith("USA:",true))null else JaArgonChannel(
                item.optString("id"),item.optString("name"),item.optString("logo"))
        }
        JaArgonChannelPage(list,doc.optInt("current_page",page),doc.optInt("total_pages",0),doc.optInt("total_results_count",0))
    }

    private suspend fun loadUsaCategories():List<JaArgonCategory> = coroutineScope {
        val first=post("search-cats-v2",1,JSONObject().put("query","USA"))
        val pages=first.optInt("total_pages",1).coerceIn(1,30)
        val docs=(2..pages).map{page->async(Dispatchers.IO){post("search-cats-v2",page,JSONObject().put("query","USA"))}}
            .awaitAll()
        (listOf(first)+docs).flatMap{doc->
            val entries=doc.optJSONArray("entries")?:JSONArray()
            (0 until entries.length()).map{entries.getJSONObject(it)}.mapNotNull{item->
                val name=item.optString("name")
                val display=item.optString("name_display").ifBlank{name}
                if(item.optString("type")!="live"||!display.startsWith("USA:",true)||name.isBlank())null
                else JaArgonCategory(name,display)
            }
        }.distinctBy{it.id}.sortedBy{it.name.lowercase()}
    }

    private fun post(endpoint:String,page:Int,payload:JSONObject):JSONObject {
        val request=Request.Builder().url("$base/$endpoint?page=$page")
            .header("User-Agent","JA/2.6.6 (https://github.com/jdaraver2011/ja_app)")
            .post(payload.toString().toRequestBody(jsonType)).build()
        client.newCall(request).execute().use{response->
            if(!response.isSuccessful)throw IOException("ArgonTV catalog HTTP ${response.code}")
            val body=response.body?:throw IOException("ArgonTV catalog response was empty")
            val raw=body.string()
            if(raw.length>2_000_000)throw IOException("ArgonTV catalog response was too large")
            val result=JSONObject(raw)
            if(result.optBoolean("error",true))throw IOException("ArgonTV catalog request was rejected")
            result
        }
    }

    private fun categoriesJson(categories:List<JaArgonCategory>):String {
        val entries=JSONArray();categories.forEach{entries.put(JSONObject().put("id",it.id).put("name",it.name))}
        return JSONObject().put("categories",entries).toString()
    }
    private fun parseCategories(raw:String):List<JaArgonCategory> {
        val entries=JSONObject(raw).getJSONArray("categories")
        return (0 until entries.length()).map{entries.getJSONObject(it)}
            .map{JaArgonCategory(it.getString("id"),it.getString("name"))}
    }
}

@Composable
internal fun JaArgonUsaCatalogDialog(repository:JaArgonCatalogRepository,onClose:()->Unit) {
    val categories= remember { mutableStateListOf<JaArgonCategory>() }
    var selected by remember { mutableStateOf<JaArgonCategory?>(null) }
    var channels by remember { mutableStateOf<JaArgonChannelPage?>(null) }
    var categorySearch by remember { mutableStateOf("") }
    var channelSearch by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(1) }
    var loadingCategories by remember { mutableStateOf(true) }
    var loadingChannels by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope=rememberCoroutineScope()
    BackHandler(onBack=onClose)

    LaunchedEffect(Unit){
        try{
            categories.clear();categories.addAll(repository.categories())
            if(selected==null)selected=categories.firstOrNull()
        }catch(e:Exception){error=e.message?:"Argon USA catalog is unavailable"}
        finally{loadingCategories=false}
    }
    LaunchedEffect(selected,channelSearch,page){
        val category=selected?:return@LaunchedEffect
        if(channelSearch.isNotBlank())delay(350)
        loadingChannels=true;error=null
        try{channels=repository.channels(category.id,channelSearch,page)}
        catch(e:Exception){error=e.message?:"Could not load this ArgonTV category";channels=null}
        finally{loadingChannels=false}
    }
    val visibleCategories=remember(categories,categorySearch){
        categories.filter{it.name.contains(categorySearch,true)||it.id.contains(categorySearch,true)}
    }

    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)){
        Row(Modifier.fillMaxSize().padding(20.dp).background(Color(0xFF172330),RoundedCornerShape(12.dp)).padding(16.dp),
            horizontalArrangement=Arrangement.spacedBy(16.dp)){
            Column(Modifier.weight(0.38f).fillMaxHeight()){
                Row(verticalAlignment=Alignment.CenterVertically){
                    Column(Modifier.weight(1f)){
                        Text("Argon USA Catalog",color=Color(0xFFE5A43B),fontSize=20.sp)
                        Text("Browse channel names · USA only",color=Color.LightGray,fontSize=12.sp)
                    }
                    TextButton(onClick={
                        loadingCategories=true;error=null
                        scope.launch{try{categories.clear();categories.addAll(repository.categories(true));selected=categories.firstOrNull();page=1}
                            catch(e:Exception){error=e.message?:"Could not refresh ArgonTV catalog"}finally{loadingCategories=false}}
                    }){Text("Refresh")}
                }
                OutlinedTextField(categorySearch,{categorySearch=it},Modifier.fillMaxWidth(),singleLine=true,
                    label={Text("Search USA categories")})
                Spacer(Modifier.height(8.dp))
                if(loadingCategories)Box(Modifier.fillMaxWidth().weight(1f),contentAlignment=Alignment.Center){CircularProgressIndicator()}
                else LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){
                    items(visibleCategories,key={it.id}){category->
                        TvFocusButton(onClick={selected=category;page=1;channelSearch=""},modifier=Modifier.fillMaxWidth(),
                            colors=ButtonDefaults.buttonColors(containerColor=if(selected==category)Color(0xFFE5A43B) else Color(0xFF263747),
                                contentColor=if(selected==category)Color.Black else Color.White)){
                            Text(category.name,maxLines=2,overflow=TextOverflow.Ellipsis,fontSize=12.sp)
                        }
                    }
                }
            }
            Column(Modifier.weight(0.62f).fillMaxHeight()){
                Row(verticalAlignment=Alignment.CenterVertically){
                    Text(selected?.name?:"Select a category",Modifier.weight(1f),color=Color.White,fontSize=18.sp,
                        maxLines=2,overflow=TextOverflow.Ellipsis)
                    TextButton(onClick=onClose){Text("Close")}
                }
                Text("Catalog information only; this page does not provide stream links.",color=Color.LightGray,fontSize=11.sp)
                OutlinedTextField(channelSearch,{channelSearch=it;page=1},Modifier.fillMaxWidth(),singleLine=true,
                    label={Text("Search channel names")})
                Spacer(Modifier.height(8.dp))
                if(loadingChannels)Box(Modifier.fillMaxWidth().weight(1f),contentAlignment=Alignment.Center){CircularProgressIndicator()}
                else LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)){
                    items(channels?.channels.orEmpty(),key={it.id}){channel->
                        Row(Modifier.fillMaxWidth().background(Color(0xFF263747),RoundedCornerShape(5.dp)).padding(8.dp),
                            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                            if(channel.logo.isNotBlank())AsyncImage(channel.logo,null,Modifier.size(28.dp))
                            Text(channel.name,color=Color.White,fontSize=13.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                        }
                    }
                }
                error?.let{Text(it,color=Color(0xFFFFAAAA),fontSize=12.sp)}
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){
                    Text("${channels?.total?:0} channels",color=Color.LightGray,fontSize=12.sp)
                    Row(verticalAlignment=Alignment.CenterVertically){
                        TextButton(enabled=page>1&&!loadingChannels,onClick={page--}){Text("Previous")}
                        Text("$page / ${(channels?.totalPages?:0).coerceAtLeast(1)}",color=Color.LightGray,fontSize=12.sp)
                        TextButton(enabled=page<(channels?.totalPages?:0)&&!loadingChannels,onClick={page++}){Text("Next")}
                    }
                }
            }
        }
    }
}
