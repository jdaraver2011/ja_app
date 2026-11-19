package com.example.ja

import android.util.Log
import androidx.compose.foundation.focusable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

internal data class JaJumpRow(val id: String, val label: String)
internal data class JaJumpRequest(val rowId: String, val generation: Long)

@Stable
internal class JaRowJumpState(private val screen: String) {
    var showing by mutableStateOf(false)
        private set
    var originId by mutableStateOf<String?>(null)
        private set
    var pending by mutableStateOf<JaJumpRequest?>(null)
        private set
    private var generation = 0L
    fun open(rowId: String) {
        if (showing) return
        originId = rowId; pending = null; showing = true
        Log.i("JA_ROW_JUMP", "OPEN screen=$screen row=$rowId")
    }
    fun choose(rowId: String) {
        showing = false; pending = JaJumpRequest(rowId, ++generation)
        Log.i("JA_ROW_JUMP", "JUMP screen=$screen row=$rowId")
    }
    fun dismiss() {
        showing = false
        originId?.let { pending = JaJumpRequest(it, ++generation) }
        Log.i("JA_ROW_JUMP", "CANCEL screen=$screen row=$originId")
    }
    fun complete(request: JaJumpRequest) { if (pending == request) pending = null }
    fun cancelPending() { pending = null }
}

/** Apply only to the first actual title (or the empty-row heading). */
internal fun Modifier.jaOpenRowJump(onOpen: () -> Unit): Modifier = onPreviewKeyEvent {
    if (it.key == Key.DirectionLeft) {
        if (it.type == KeyEventType.KeyDown) onOpen()
        true
    } else false
}

/** A delayed network response must not reclaim focus after the user moves away. */
internal fun Modifier.jaCancelPendingRowJump(state: JaRowJumpState, returnFocus: JaTitleReturnFocus): Modifier = onPreviewKeyEvent {
    if (it.type == KeyEventType.KeyDown && it.key in listOf(
            Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown,
            Key.DirectionCenter, Key.Enter) && state.pending != null) {
        returnFocus.pending?.let { key -> returnFocus.restored(key) }
        state.cancelPending()
    }
    false
}

internal fun Modifier.jaRowJumpFirstTitle(state: JaRowJumpState, rowId: String): Modifier =
    jaOpenRowJump { state.open(rowId) }.onFocusChanged {
        if (it.isFocused) state.pending?.takeIf { request -> request.rowId == rowId }?.let(state::complete)
    }

@Composable
internal fun JaRowJumpScrollEffect(state: JaRowJumpState, rows: List<JaJumpRow>, list: LazyListState) {
    val request = state.pending
    LaunchedEffect(request, rows) {
        request ?: return@LaunchedEffect
        val index = JaRowJumpIndex(rows.map { it.id }.toTypedArray(), rows.map { it.label }.toTypedArray())
            .rowIndex(request.rowId)
        if (index >= 0) list.scrollToItem(index) else state.complete(request)
    }
}

@Composable
internal fun JaRowJumpTargetEffect(
    state: JaRowJumpState, rowId: String, row: LazyListState,
    firstTitleKey: String?, returnFocus: JaTitleReturnFocus,
    emptyRequester: FocusRequester, loading: Boolean
) {
    val request = state.pending?.takeIf { it.rowId == rowId }
    LaunchedEffect(request, firstTitleKey, loading) {
        request ?: return@LaunchedEffect
        if (firstTitleKey != null) row.scrollToItem(0)
        withFrameNanos { }; withFrameNanos { }
        if (state.pending != request) return@LaunchedEffect
        if (firstTitleKey != null) {
            returnFocus.focus(firstTitleKey)
        } else {
            emptyRequester.requestFocus()
            if (!loading) state.complete(request)
        }
    }
}

@Composable
internal fun JaRowJumpButton(state: JaRowJumpState, rows: List<JaJumpRow>, list: LazyListState) {
    TvFocusButton(
        onClick = { rows.getOrNull(list.firstVisibleItemIndex)?.let { state.open(it.id) } },
        enabled = rows.isNotEmpty(), modifier = Modifier.height(32.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
    ) { Text("Jump to row", fontSize = 12.sp, color = Color.White) }
}

@Composable
internal fun JaRowJumpHeading(label: String, requester: FocusRequester, empty: Boolean, onOpen: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(label, style = MaterialTheme.typography.titleLarge, color = Color.White,
        modifier = Modifier.focusRequester(requester).onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(4.dp)) else Modifier)
            .jaOpenRowJump(onOpen).focusable(enabled = empty).padding(3.dp))
}

@Composable
internal fun JaRowJumpPanel(state: JaRowJumpState, rows: List<JaJumpRow>, networks: Boolean) {
    if (!state.showing || rows.isEmpty()) return
    val origin = state.originId ?: rows.first().id
    val scope = rememberCoroutineScope()
    val sorted = remember(rows) { rows.sortedBy { it.label.lowercase(java.util.Locale.ROOT) } }
    val initialLetter = JaRowJumpIndex.initial(rows.firstOrNull { it.id == origin }?.label)
    var letter by remember { mutableStateOf(initialLetter) }
    val letters = remember(rows) {
        (if (rows.any { JaRowJumpIndex.initial(it.label) == "#" }) listOf("#") else emptyList()) +
            ('A'..'Z').map { it.toString() }
    }
    val matches = if (networks) sorted.filter { JaRowJumpIndex.initial(it.label) == letter } else sorted
    val namesState = rememberLazyListState(initialFirstVisibleItemIndex =
        matches.indexOfFirst { it.id == origin }.coerceAtLeast(0))
    val lettersState = rememberLazyListState(initialFirstVisibleItemIndex =
        letters.indexOf(initialLetter).coerceAtLeast(0))
    val nameRequesters = remember(matches) { matches.associate { it.id to FocusRequester() } }
    val letterRequesters = remember(letters) { letters.associateWith { FocusRequester() } }
    LaunchedEffect(letter) {
        if (networks) namesState.scrollToItem(matches.indexOfFirst { it.id == origin }.coerceAtLeast(0))
    }
    Dialog(onDismissRequest = { state.dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LaunchedEffect(Unit) {
            withFrameNanos { }; withFrameNanos { }
            if (networks) letterRequesters[initialLetter]?.requestFocus()
            else nameRequesters[origin]?.requestFocus()
        }
        val menuDensity = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(menuDensity.density * 0.5f, menuDensity.fontScale)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val panelWidth = minOf(360.dp, maxWidth * 0.43f)
            Row(Modifier.fillMaxSize().onKeyEvent {
                if (it.key == Key.DirectionRight) {
                    if (it.type == KeyEventType.KeyDown) state.dismiss()
                    true
                } else false
            }) {
                Surface(Modifier.width(panelWidth).fillMaxHeight(0.5f), color = Color(0xFF1B2430)) {
                    Column(Modifier.fillMaxSize().padding(12.dp)) {
                        Text(if (networks) "Jump to network" else "Jump to genre",
                            style = MaterialTheme.typography.titleLarge, color = Color.White)
                        Text(if (networks) "OK on a letter jumps. Right selects a network. Back closes."
                            else "Choose a genre. Back or Right closes.",
                            style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
                        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (networks) LazyColumn(state = lettersState, modifier = Modifier.width(60.dp).fillMaxHeight()) {
                                items(letters, key = { it }) { item ->
                                    val available = rows.any { JaRowJumpIndex.initial(it.label) == item }
                                    TvFocusButton(
                                        enabled = available,
                                        onClick = { sorted.firstOrNull { JaRowJumpIndex.initial(it.label) == item }?.let { state.choose(it.id) } },
                                        modifier = Modifier.fillMaxWidth().focusRequester(letterRequesters.getValue(item))
                                            .onFocusChanged { if (it.isFocused) letter = item }
                                            .onPreviewKeyEvent { event ->
                                                if (event.key == Key.DirectionRight) {
                                                    if (event.type == KeyEventType.KeyDown) scope.launch {
                                                        val target = matches.firstOrNull() ?: return@launch
                                                        namesState.scrollToItem(0)
                                                        withFrameNanos { }; withFrameNanos { }
                                                        nameRequesters[target.id]?.requestFocus()
                                                    }
                                                    true
                                                } else false
                                            },
                                        colors = ButtonDefaults.buttonColors(containerColor =
                                            if (letter == item) Color(0xFFE5A43B) else Color.DarkGray)
                                    ) { Text(item, color = if (letter == item) Color.Black else Color.White) }
                                }
                            }
                            LazyColumn(state = namesState, modifier = Modifier.weight(1f).fillMaxHeight()) {
                                items(matches, key = { it.id }) { item ->
                                    TvFocusButton(onClick = { state.choose(item.id) },
                                        modifier = Modifier.fillMaxWidth().focusRequester(nameRequesters.getValue(item.id))
                                            .onPreviewKeyEvent { event ->
                                                if (networks && event.key == Key.DirectionLeft) {
                                                    if (event.type == KeyEventType.KeyDown) scope.launch {
                                                        lettersState.scrollToItem(letters.indexOf(letter).coerceAtLeast(0))
                                                        withFrameNanos { }; withFrameNanos { }
                                                        letterRequesters[letter]?.requestFocus()
                                                    }
                                                    true
                                                } else false
                                            },
                                        colors = ButtonDefaults.buttonColors(containerColor =
                                            if (item.id == origin) Color(0xFFE5A43B) else Color.DarkGray)
                                    ) { Text(item.label, color = if (item.id == origin) Color.Black else Color.White) }
                                }
                            }
                        }
                        TvFocusButton(onClick = { state.dismiss() }) { Text("Close", color = Color.White) }
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight().pointerInput(state) {
                    detectTapGestures { state.dismiss() }
                })
            }
        }
        }
    }
}
