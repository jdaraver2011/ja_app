package com.example.ja

import androidx.activity.compose.BackHandler
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
    OutlinedTextField(value = entry, onValueChange = { value ->
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
            if (JaWeatherRules.validZip(entry)) { state.saveZip(entry); message = "Weather ZIP code saved." }
            else message = "Enter a five-digit U.S. ZIP code."
        }) { Text("Save ZIP code") }
        TvFocusButton(onClick = { entry = ""; state.saveZip(""); message = "Weather ZIP code cleared." }) { Text("Clear") }
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
        .focusRequester(state.cardFocus).onFocusChanged { focused = it.isFocused }
        .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color(0xFF394655), RoundedCornerShape(8.dp))
        .background(Color(0xFF1C2632), RoundedCornerShape(8.dp)).clickable(onClick = onClick)) {
        val compact = maxHeight < 65.dp
        val scale = if (compact) 1f else minOf(maxWidth.value / 216f, maxHeight.value / 76f).coerceAtLeast(0.5f)
        val inset = (if (compact) 5f else 8f * scale).dp
        val summary = when {
            state.zip.isBlank() -> "Set preferred ZIP code in Settings"
            state.loading -> "Loading weather for ${state.zip}…"
            else -> state.error ?: "Open weather for ${state.zip}"
        }
        Column(Modifier.fillMaxSize().padding(inset), verticalArrangement = Arrangement.SpaceBetween) {
            Text(data?.place ?: "Local weather", color = weatherGold, fontSize = (if (compact) 11f else 13f * scale).sp,
                lineHeight = (if (compact) 13f else 16f * scale).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (data != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    data.icon?.let { AsyncImage(it, null, Modifier.size((if (compact) 20f else 32f * scale).dp)) }
                    Text("${data.temperature}  ${data.conditions}", color = Color.White,
                        fontSize = (if (compact) 12f else 13f * scale).sp,
                        lineHeight = (if (compact) 14f else 16f * scale).sp, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
                if (!compact) Text(when {
                    data.stale -> "Saved / delayed weather · Open details"
                    !data.alertsAvailable -> "Alerts unavailable · Open details"
                    data.alerts.isNotEmpty() -> "${data.alerts.size} active alert(s) · Open details"
                    else -> "Current conditions · Open details"
                }, color = if (data.alerts.isNotEmpty()) Color(0xFFFFB6A8) else Color.LightGray, fontSize = (10f * scale).sp, lineHeight = (12f * scale).sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else Text(summary, color = Color.White, fontSize = (if (compact) 10f else 12f * scale).sp,
                lineHeight = (if (compact) 12f else 14f * scale).sp, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
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
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF111725)).padding(16.dp)) {
        val wide = maxWidth >= 700.dp && maxWidth > maxHeight
        val pageScroll = rememberScrollState()
        Column(if (wide) Modifier.fillMaxSize() else Modifier.fillMaxSize().verticalScroll(pageScroll),
            verticalArrangement = Arrangement.spacedBy(if (wide) 6.dp else 10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvFocusButton(onClick = onClose, modifier = Modifier.focusRequester(firstFocus)) { Text("Back") }
                Column(Modifier.weight(1f)) {
                    Text("Current Conditions", color = weatherGold, fontSize = if (wide) 22.sp else 19.sp)
                    data?.let { Text("${it.place} · ${it.zip}", color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                if (wide) JaHeaderClock()
            }
            if (!wide) JaHeaderClock(Modifier.fillMaxWidth())
            if (data == null) {
                Text(if (state.zip.isBlank()) "Set your preferred U.S. ZIP code to display local weather." else
                    state.error ?: "Loading weather for ${state.zip}…", color = Color.White)
                TvFocusButton(onClick = onSettings) { Text("Weather settings") }
                if (state.zip.isNotBlank()) TvFocusButton(onClick = { state.revision++ }, enabled = !state.loading) { Text("Retry") }
            } else {
                if (data.stale) Text("Saved or delayed observations — check the observation time below.", color = weatherGold)
                if (!data.alertsAvailable) Text("Active alerts could not be refreshed. Alert status is unavailable.", color = weatherGold)
                val selectedAlert = data.alerts.getOrNull(alertIndex)
                if (wide) Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    WeatherAlertList(data, alertIndex, Modifier.weight(0.23f).fillMaxHeight().verticalScroll(rememberScrollState())) { alertIndex = it }
                    WeatherConditions(data, Modifier.weight(0.51f).fillMaxHeight().verticalScroll(rememberScrollState()).focusable())
                    WeatherAffectedArea(selectedAlert, Modifier.weight(0.26f).fillMaxHeight().verticalScroll(rememberScrollState()).focusable())
                } else {
                    WeatherConditions(data, Modifier.fillMaxWidth())
                    WeatherAlertList(data, alertIndex, Modifier.fillMaxWidth()) { alertIndex = it }
                    WeatherAffectedArea(selectedAlert, Modifier.fillMaxWidth())
                }
                selectedAlert?.let { a ->
                    if (wide) TvFocusButton(onClick = { alertDetails = true }, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = weatherRed)) {
                        Text("${a.event} · Open full alert", color = Color.White, fontSize = 13.sp, maxLines = 1)
                    } else Column(Modifier.fillMaxWidth().background(weatherRed).padding(12.dp)) {
                        Text(a.headline.ifBlank { a.event }, color = Color.White, fontSize = 16.sp)
                        Text(a.description, color = Color.White, fontSize = 13.sp)
                        Text("Expires: ${weatherTime(a.expires)}", color = weatherGold, fontSize = 12.sp)
                    }
                    if (alertDetails) AlertDialog(onDismissRequest = { alertDetails = false },
                        title = { Text(a.event) },
                        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                            Text(a.headline); Text(a.area); Text(a.description)
                            if (a.instruction.isNotBlank()) Text(a.instruction)
                            Text("Expires: ${weatherTime(a.expires)}")
                        } }, confirmButton = { TvFocusButton(onClick = { alertDetails = false }) { Text("Close") } })
                }
                Text("Observed: ${weatherTime(data.observationTime)} · ${data.station}", color = Color.LightGray, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvFocusButton(onClick = { state.revision++ }, enabled = !state.loading) { Text(if (state.loading) "Refreshing…" else "Refresh") }
                    TvFocusButton(onClick = onSettings) { Text("Change ZIP code") }
                }
            }
            Text("Source: National Weather Service · Missing measurements shown as — · ZIP lookup: Zippopotam.us",
                color = Color.LightGray, fontSize = 11.sp)
            if (!wide) Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun WeatherConditions(data: JaWeatherData, modifier: Modifier) {
    Column(modifier.background(weatherBlue, RoundedCornerShape(6.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(data.temperature, color = Color.White, fontSize = 38.sp)
                Text(data.conditions, color = Color.White, fontSize = 18.sp)
            }
            data.icon?.let { AsyncImage(it, data.conditions, Modifier.size(68.dp)) }
        }
        for ((label, value) in listOf("Wind" to data.wind, "Humidity" to data.humidity, "Dewpoint" to data.dewpoint,
            "Ceiling" to data.ceiling, "Visibility" to data.visibility, "Pressure" to data.pressure, "Wind chill" to data.windChill)) {
            Row(Modifier.fillMaxWidth()) {
                Text("$label:", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Text(value, color = Color.White, fontSize = 16.sp, textAlign = TextAlign.End)
            }
        }
    }
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
