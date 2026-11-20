package com.example.ja

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val weatherGold = Color(0xFFE5A43B)
private val weatherBlue = Color(0xFF1E2B5E)
private val weatherRed = Color(0xFF631F28)

@Stable
internal class JaWeatherState(val repository: JaWeatherRepository) {
    var zip by mutableStateOf(repository.savedZip())
    var data by mutableStateOf<JaWeatherData?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var revision by mutableIntStateOf(0)
    var completedRevision = 0
    val cardFocus = FocusRequester()
    val navigationFocus = FocusRequester()
    var returnToNavigation: (() -> Unit)? = null
    fun saveZip(value: String) {
        if (value == zip) return
        repository.saveZip(value); data = null; error = null; zip = value
    }
}

@Composable
internal fun rememberJaWeather(active: Boolean): JaWeatherState {
    val context = LocalContext.current.applicationContext
    val state = remember { JaWeatherState(JaWeatherRepository(context)) }
    val owner = LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.zip, state.revision, active, resumed) {
        if (!active || !resumed || !JaWeatherRules.validZip(state.zip)) return@LaunchedEffect
        val zip = state.zip
        val requestedRevision = state.revision
        var force = requestedRevision > state.completedRevision
        while (isActive) {
            state.loading = true
            try {
                val result = state.repository.load(zip, force)
                if (state.zip == zip) { state.data = result; state.error = null }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { if (state.zip == zip) state.error = "Weather unavailable. Check your ZIP code or connection." }
            finally { state.completedRevision = requestedRevision; state.loading = false }
            force = false
            // Resume checks the existing snapshot's age instead of restarting its 15-minute clock.
            delay(JaWeatherRules.refreshDelay(state.data?.takeIf { it.zip == zip }?.fetchedAt ?: 0L,
                System.currentTimeMillis()))
        }
    }
    return state
}

/** Clock recomposes on its own; catalog grids do not tick every minute. */
@Composable
internal fun JaHeaderClock(modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while (isActive) { now = Instant.now(); delay(1000) } }
    val formatter = remember { DateTimeFormatter.ofPattern("MM-dd-yyyy hh:mm a", Locale.US) }
    Text(formatter.withZone(ZoneId.systemDefault()).format(now), modifier,
        color = Color.White, fontSize = 14.sp, maxLines = 1, textAlign = TextAlign.End)
}

@Composable
internal fun JaWeatherSettings(state: JaWeatherState) {
    var entry by rememberSaveable { mutableStateOf(state.zip) }
    var message by remember { mutableStateOf<String?>(null) }
    Text("Weather", style = MaterialTheme.typography.titleLarge, color = Color.White)
    JaEntryField(value = entry, onValueChange = { value ->
        entry = value.filter { it in '0'..'9' }.take(5); message = null
    }, label = { Text("Preferred ZIP code") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White,
            unfocusedTextColor = Color.White, focusedBorderColor = weatherGold,
            unfocusedBorderColor = Color.LightGray, focusedLabelColor = weatherGold,
            unfocusedLabelColor = Color.LightGray))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TvFocusButton(onClick = {
            if (JaWeatherRules.validZip(entry)) { state.saveZip(entry); message = "Preferred ZIP code saved." }
            else message = "Enter a five-digit U.S. ZIP code."
        }) { Text("Save ZIP code") }
        TvFocusButton(onClick = { entry = ""; state.saveZip(""); message = "Preferred ZIP code cleared." }) { Text("Clear") }
    }
    message?.let { Text(it, color = Color.LightGray) }
    Text("Saved on this device. U.S. weather: National Weather Service. ZIP lookup: Zippopotam.us. No location permission required.",
        color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(12.dp))
}

@Composable
internal fun JaWeatherCard(state: JaWeatherState, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val data = state.data?.takeIf { it.zip == state.zip }
    BoxWithConstraints(modifier
        .onPreviewKeyEvent { event ->
            if (event.key == Key.DirectionLeft && state.returnToNavigation != null) {
                if (event.type == KeyEventType.KeyDown) state.returnToNavigation?.invoke()
                true
            } else false
        }.focusRequester(state.cardFocus).onFocusChanged { focused = it.isFocused }
        .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color(0xFF394655), RoundedCornerShape(8.dp))
        .background(Color(0xFF1C2632), RoundedCornerShape(8.dp)).clickable(onClick = onClick)) {
        val compact = maxHeight < 65.dp
        val scale = if (compact) 1f else minOf(maxWidth.value / 216f, maxHeight.value / 120f).coerceAtLeast(0.5f)
        val inset = (if (compact) 3f else 8f * scale).dp
        val summary = when {
            state.zip.isBlank() -> "Set preferred ZIP code in Settings"
            state.loading -> "Loading weather for ${state.zip}…"
            else -> state.error ?: "Open weather for ${state.zip}"
        }
        Column(Modifier.fillMaxSize().padding(inset), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy((if (compact) 1f else 4f * scale).dp, Alignment.CenterVertically)) {
            Text(data?.place ?: "Local weather", color = weatherGold, fontSize = (if (compact) 11f else 13f * scale).sp,
                lineHeight = (if (compact) 12f else 16f * scale).sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            if (data != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center) {
                    data.icon?.let {
                        AsyncImage(it, null, Modifier.size((if (compact) 16f else 32f * scale).dp))
                        Spacer(Modifier.width((if (compact) 6f else 8f * scale).dp))
                    }
                    Text("${data.temperature}  ${data.conditions}", color = Color.White,
                        fontSize = (if (compact) 12f else 13f * scale).sp,
                        lineHeight = (if (compact) 14f else 16f * scale).sp, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.weight(1f, fill = false))
                }
                if(compact) Text("${data.wind} · Humidity ${data.humidity}",color=Color.LightGray,fontSize=9.sp,lineHeight=10.sp,maxLines=1,
                    overflow=TextOverflow.Ellipsis,textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth())
                if (!compact) {
                    Text("Wind ${data.wind} · Humidity ${data.humidity}", color = Color.White,
                        fontSize = (10f*scale).sp, lineHeight = (14f*scale).sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    val next = data.forecast.firstOrNull()
                    Text(next?.let { "${it.name}: ${it.temperature} · ${it.description}" } ?: "Observed ${weatherTime(data.observationTime)}",
                        color = Color.LightGray, fontSize = (10f*scale).sp, lineHeight = (14f*scale).sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
                if (!compact) Text(when {
                    data.stale -> "Saved / delayed weather · Open details"
                    !data.alertsAvailable -> "Alerts unavailable · Open details"
                    data.alerts.isNotEmpty() -> "${data.alerts.size} active alert(s) · Open details"
                    else -> "Current conditions · Open details"
                }, color = if (data.alerts.isNotEmpty()) Color(0xFFFFB6A8) else Color.LightGray, fontSize = (10f * scale).sp, lineHeight = (12f * scale).sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            } else Text(summary, color = Color.White, fontSize = (if (compact) 10f else 12f * scale).sp,
                lineHeight = (if (compact) 12f else 14f * scale).sp, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun weatherTime(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("MM-dd-yyyy hh:mm a z", Locale.US).withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault("Unavailable")

@Composable
internal fun JaWeatherPage(state: JaWeatherState, onClose: () -> Unit, onSettings: () -> Unit) {
    BackHandler(onBack = onClose)
    val data = state.data
    var alertIndex by remember(data?.zip, data?.fetchedAt) { mutableIntStateOf(0) }
    var alertDetails by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.requestFocus() }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF111725)).padding(12.dp)) {
        val wide = maxWidth >= 700.dp && maxWidth > maxHeight
        Column(if (wide) Modifier.fillMaxSize() else Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                var focused by remember { mutableStateOf(false) }
                FilledIconButton(onClick = { state.revision++ }, enabled = !state.loading,
                    modifier = Modifier.size(36.dp).focusRequester(firstFocus).onFocusChanged { focused=it.isFocused }
                        .border(if(focused)2.dp else 0.dp, if(focused)Color.White else Color.Transparent, RoundedCornerShape(18.dp))
                        .semantics { contentDescription = "Refresh weather" }) {
                    val symbolColor=LocalContentColor.current
                    Canvas(Modifier.size(24.dp)) {
                        val radius=size.minDimension*0.34f
                        val middle=center
                        drawArc(symbolColor,30f,285f,false,Offset(middle.x-radius,middle.y-radius),Size(radius*2,radius*2),
                            style=Stroke(width=2.dp.toPx(),cap=StrokeCap.Round))
                        val tip=Offset(middle.x+radius*0.7071f,middle.y-radius*0.7071f)
                        val back=6.dp.toPx();val side=3.5.dp.toPx()
                        val arrow=Path().apply {
                            moveTo(tip.x,tip.y)
                            lineTo(tip.x-0.7071f*(back+side),tip.y-0.7071f*(back-side))
                            lineTo(tip.x-0.7071f*(back-side),tip.y-0.7071f*(back+side));close()
                        }
                        drawPath(arrow,symbolColor)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text("Current Conditions", color=weatherGold,fontSize=20.sp,lineHeight=24.sp)
                    Text(data?.let { "${it.place} · ${it.zip}" } ?: "Local weather",color=Color.White,fontSize=12.sp,lineHeight=15.sp)
                }
                if(wide) JaHeaderClock()
            }
            if(!wide) JaHeaderClock(Modifier.fillMaxWidth())
            if(data==null) {
                Text(if(state.zip.isBlank()) "Set your preferred ZIP code in Settings." else state.error ?: "Loading weather…", color=Color.White)
                TvFocusButton(onClick=onSettings) { Text("Weather settings") }
            } else {
                if(data.stale) Text("Saved/delayed observation · ${weatherTime(data.observationTime)}",color=weatherGold,fontSize=11.sp)
                if(!data.alertsAvailable) Text("Alert status unavailable",color=weatherGold,fontSize=11.sp)
                val alert=data.alerts.getOrNull(alertIndex)
                if(wide) Row(Modifier.fillMaxWidth().weight(1f),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    WeatherAlertList(data,alertIndex,Modifier.weight(0.22f).fillMaxHeight().verticalScroll(rememberScrollState())) { alertIndex=it }
                    WeatherConditions(data,state.revision,Modifier.weight(0.56f).fillMaxHeight())
                    WeatherAffectedArea(alert,Modifier.weight(0.22f).fillMaxHeight().verticalScroll(rememberScrollState()).focusable())
                } else {
                    WeatherConditions(data,state.revision,Modifier.fillMaxWidth())
                    WeatherAlertList(data,alertIndex,Modifier.fillMaxWidth()) { alertIndex=it }
                    WeatherAffectedArea(alert,Modifier.fillMaxWidth())
                }
                if(alert!=null) {
                    TvFocusButton(onClick={alertDetails=true},colors=ButtonDefaults.buttonColors(containerColor=weatherRed)) {
                        Text("${alert.event} · Full alert",color=Color.White,fontSize=12.sp,maxLines=1)
                    }
                    if(alertDetails) AlertDialog(onDismissRequest={alertDetails=false},title={Text(alert.event)},
                        text={Column(Modifier.verticalScroll(rememberScrollState())) {
                            Text(alert.headline);Text(alert.area);Text(alert.description)
                            if(alert.instruction.isNotBlank())Text(alert.instruction)
                            Text("Expires: ${weatherTime(alert.expires)}")
                        }},confirmButton={TvFocusButton(onClick={alertDetails=false}){Text("Close")}})
                }
                WeatherExtendedForecast(data)
                Text("Observed: ${weatherTime(data.observationTime)} · ${data.station}",color=Color.LightGray,fontSize=10.sp,lineHeight=12.sp,maxLines=2)
                Text("Updated: ${weatherTime(Instant.ofEpochMilli(data.fetchedAt).toString())}${if(state.loading) " · Updating…" else " · Automatic refresh every 15 minutes"}",
                    color=Color.LightGray,fontSize=10.sp,lineHeight=12.sp,maxLines=1)
            }
        }
    }
}

@Composable
private fun WeatherConditions(data:JaWeatherData,revision:Int,modifier:Modifier) {
    BoxWithConstraints(modifier.background(weatherBlue,RoundedCornerShape(6.dp))) {
        val bounded=constraints.hasBoundedHeight
        val roomy=bounded && maxHeight>=260.dp
        val body=when {
            roomy->Modifier.fillMaxSize()
            bounded->Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            else->Modifier.fillMaxWidth()
        }
        Column(body.padding(10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text(data.temperature,color=Color.White,fontSize=28.sp,lineHeight=32.sp)
                Spacer(Modifier.width(10.dp))
                Text(data.conditions,color=Color.White,fontSize=14.sp,lineHeight=18.sp,modifier=Modifier.weight(1f),maxLines=2)
                data.icon?.let{AsyncImage(it,null,Modifier.size(40.dp))}
            }
            val fields=listOf("Wind" to data.wind,"Humidity" to data.humidity,"Dewpoint" to data.dewpoint,
                "Ceiling" to data.ceiling,"Visibility" to data.visibility,"Pressure" to data.pressure,"Wind chill" to data.windChill)
            Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                for(pair in fields.chunked(2)) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    pair.forEach{(label,value)->Text("$label: $value",color=Color.White,fontSize=12.sp,lineHeight=16.sp,
                        modifier=Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis)}
                }
            }
            JaWeatherRadarWindow(data,revision,if(roomy)Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().height(200.dp))
        }
    }
}

@Composable
private fun WeatherExtendedForecast(data: JaWeatherData) {
    var expanded by remember(data.zip) { mutableStateOf<JaForecastPeriod?>(null) }
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text("Extended Forecast${if(!data.forecastAvailable) " · Forecast update unavailable" else ""}",color=weatherGold,fontSize=15.sp,lineHeight=18.sp)
        if(data.forecast.isEmpty()) Text("Forecast unavailable",color=Color.LightGray,fontSize=12.sp)
        else LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            items(data.forecast,key={it.start}) { period ->
                var focused by remember { mutableStateOf(false) }
                Column(Modifier.width(100.dp).height(118.dp).onFocusChanged {focused=it.isFocused}
                    .border(if(focused)2.dp else 1.dp,if(focused)Color.White else Color(0xFF33445E),RoundedCornerShape(5.dp))
                    .background(Color(0xFF1C2632)).clickable {expanded=period}.padding(5.dp),horizontalAlignment=Alignment.CenterHorizontally,
                    verticalArrangement=Arrangement.spacedBy(2.dp)) {
                    Text(period.name,color=Color.White,fontSize=11.sp,lineHeight=13.sp,maxLines=1)
                    period.icon?.let{AsyncImage(it,null,Modifier.size(36.dp))}
                    Text("${if(period.day) "High" else "Low"} ${period.temperature}",color=if(period.day)Color(0xFFFFBA91) else Color(0xFF8ED5FF),fontSize=11.sp,lineHeight=13.sp)
                    Text(period.description,color=Color.White,fontSize=10.sp,lineHeight=12.sp,maxLines=2,overflow=TextOverflow.Ellipsis,textAlign=TextAlign.Center)
                    if(period.rainChance.isNotBlank()) Text("Rain ${period.rainChance}",color=Color.LightGray,fontSize=9.sp,lineHeight=10.sp)
                }
            }
        }
    }
    expanded?.let{p->AlertDialog(onDismissRequest={expanded=null},title={Text(p.name)},text={Text("${p.temperature} · ${p.description}\n${p.rainChance.takeIf{it.isNotBlank()}?.let{"Precipitation: $it"}.orEmpty()}")},
        confirmButton={TvFocusButton(onClick={expanded=null}){Text("Close")}})}
}

@Composable
private fun WeatherAlertList(data: JaWeatherData, selected: Int, modifier: Modifier, onSelect: (Int) -> Unit) {
    Column(modifier.background(weatherRed, RoundedCornerShape(6.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("LOCAL ALERTS", color = weatherGold, fontSize = 14.sp)
        if (data.alerts.isEmpty()) Text(if (!data.alertsAvailable) "Alert status unavailable" else if (data.stale)
            "No alerts in saved weather. Refresh for current status." else "No active alerts", color = Color.White, fontSize = 14.sp)
        data.alerts.forEachIndexed { index, alert ->
            TvFocusButton(onClick = { onSelect(index) }, colors = ButtonDefaults.buttonColors(
                containerColor = if (selected == index) Color(0xFF94333C) else Color(0xFF492028))) {
                Text("${alert.event}\n${alert.severity}", color = Color.White, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun WeatherAffectedArea(alert: JaWeatherAlert?, modifier: Modifier) {
    Column(modifier.background(weatherRed, RoundedCornerShape(6.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("AFFECTED AREA", color = weatherGold, fontSize = 14.sp)
        Text(alert?.area?.ifBlank { "Area unavailable" } ?: "No alert selected", color = Color.White, fontSize = 13.sp)
        alert?.instruction?.takeIf { it.isNotBlank() }?.let { Text(it, color = Color.White, fontSize = 13.sp) }
    }
}
