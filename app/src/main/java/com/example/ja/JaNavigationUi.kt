package com.example.ja

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Lives above details routes; each occurrence has a tab/section-specific key. */
@Stable
class JaTitleReturnFocus {
    private var origin: String? = null
    var pending by mutableStateOf<String?>(null)
        private set
    fun opened(key: String) { origin = key; pending = null }
    fun returned() { pending = origin }
    fun restored(key: String) { if (pending == key) pending = null }
    fun clear() { origin = null; pending = null }
}

@Composable
internal fun rememberTitleReturnRequester(state: JaTitleReturnFocus?, key: String?): FocusRequester {
    val requester = remember(key) { FocusRequester() }
    val pending = state?.pending == key && key != null
    LaunchedEffect(pending) {
        if (pending) {
            // Allow retained lazy-list positions and Search's bookmark to settle.
            withFrameNanos { }
            withFrameNanos { }
            requester.requestFocus()
        }
    }
    return requester
}

/** Same dimensions, padding and typography as Favorites' Movies/TV selectors. */
@Composable
internal fun JaCompactBackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Button(
        onClick = onClick,
        modifier = modifier.height(26.dp)
            .onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(18.dp)) else Modifier),
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray, contentColor = Color.White)
    ) { Text("← Back", fontSize = 11.sp, maxLines = 1) }
}
