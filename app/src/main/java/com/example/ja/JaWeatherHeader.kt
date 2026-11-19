package com.example.ja

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Pixel bounds bridge the unscaled navigation and the compact catalogue density. */
@Stable
internal class JaWeatherHeaderState {
    var root by mutableStateOf(Rect.Zero)
    var navigation by mutableStateOf(Rect.Zero)
    var settings by mutableStateOf(Rect.Zero)
    var contentTop by mutableFloatStateOf(0f)
    var side by mutableStateOf(false)
}
private val LocalWeatherHeader = staticCompositionLocalOf<JaWeatherHeaderState?> { null }

/** Only introductory text uses the left area; title grids retain the entire screen width. */
@Composable
internal fun Modifier.jaWeatherIntroWidth(): Modifier {
    val state = LocalWeatherHeader.current
    val width = if (state?.side == true) with(LocalDensity.current) {
        (state.settings.right - state.root.left).coerceAtLeast(0f).toDp()
    } else Dp.Infinity
    return widthIn(max = width)
}

/** Attach before a stationary list/grid, never to a moving lazy-list item. */
@Composable
internal fun Modifier.jaWeatherContentStart(): Modifier {
    val state = LocalWeatherHeader.current
    return if (state == null) this else onGloballyPositioned { state.contentTop = it.boundsInRoot().top }
}

@Composable
internal fun JaWeatherHeaderFrame(
    state: JaWeatherHeaderState, weather: JaWeatherState, navigation: LazyListState,
    onOpen: () -> Unit, content: @Composable (Boolean) -> Unit
) {
    val density = LocalDensity.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { state.root = it.boundsInRoot() }) {
        val gap = with(density) { 12.dp.toPx() }
        val minWidth = with(density) { 160.dp.toPx() }
        val widthMatches = kotlin.math.abs(state.root.width - constraints.maxWidth) <= 1f
        val side = landscape && widthMatches && navigation.firstVisibleItemIndex == 0 &&
            navigation.firstVisibleItemScrollOffset == 0 && state.settings.width > 0 &&
            state.settings.left >= state.root.left && state.settings.right + gap + minWidth <= state.root.right
        SideEffect { state.side = side }
        CompositionLocalProvider(LocalWeatherHeader provides state) { content(side) }
        if (side) {
            val left = state.settings.right + gap
            val top = state.navigation.top
            val bottom = maxOf(state.navigation.bottom, state.contentTop - with(density) { 6.dp.toPx() })
                .coerceAtMost(state.root.bottom)
            val width = (state.root.right - left).coerceAtLeast(1f)
            val height = (bottom - top).coerceAtLeast(1f)
            JaWeatherCard(weather, Modifier.offset {
                IntOffset((left - state.root.left).roundToInt(), (top - state.root.top).roundToInt())
            }.size(with(density) { width.toDp() }, with(density) { height.toDp() }), onClick = onOpen)
        }
    }
}
