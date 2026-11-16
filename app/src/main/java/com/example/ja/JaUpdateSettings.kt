package com.example.ja

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun JaUpdateSettings() {
    val context = LocalContext.current
    val updater = remember { JaAppUpdates(context.applicationContext) }
    val state by updater.state.collectAsState()
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }
    Text("App updates", style = MaterialTheme.typography.titleLarge, color = Color.White)
    Text("Installed version: ${BuildConfig.VERSION_NAME}", color = Color.LightGray)
    Button(
        onClick = { if(state.ready) updater.install(context) else scope.launch { updater.updateNow() } },
        enabled = !state.busy,
        modifier = Modifier.onFocusChanged { focused = it.isFocused },
        border = jaButtonFocusBorder(focused)
    ) { Text(if(state.ready) "Install Update" else if(state.busy) "Updating…" else "Update Now") }
    if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if(state.message.isNotBlank()) Text(state.message, color = Color.LightGray)
    Text("Checks published updates over the internet. Android will ask you to confirm installation.",
        color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(16.dp))
}
