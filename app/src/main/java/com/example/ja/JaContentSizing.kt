package com.example.ja

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp

/** Layout-aware sizing: dp, sp, padding and focus bounds shrink together. No drawing-only scale. */
// A further 5% reduction from the established 80% content size.
private const val CONTENT_SCALE = 0.8f * 0.95f
private val LocalJaBaseDensity = staticCompositionLocalOf<Density?> { null }

@Composable
internal fun JaCompactContent(content: @Composable () -> Unit) {
    val base = LocalJaBaseDensity.current ?: LocalDensity.current
    CompositionLocalProvider(
        LocalJaBaseDensity provides base,
        LocalDensity provides Density(base.density * CONTENT_SCALE, base.fontScale),
        content = content
    )
}

/** Keep both main navigation rows at the system density inside compact content. */
@Composable
internal fun JaUnscaledTopBar(content: @Composable () -> Unit) {
    val base = LocalJaBaseDensity.current ?: LocalDensity.current
    CompositionLocalProvider(LocalDensity provides base, content = content)
}

/** Keep established phone/tablet detail breakpoints while shrinking their contents uniformly. */
@Composable
internal fun jaContentBaseWidth(width: Dp): Dp {
    val base = LocalJaBaseDensity.current ?: LocalDensity.current
    return width * (LocalDensity.current.density / base.density)
}
