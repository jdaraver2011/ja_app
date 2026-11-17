package com.example.ja

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.mozilla.geckoview.GeckoView

@Composable
internal fun JaMusicScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by JaMusicService.state.collectAsState()
    var confirmReset by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun open(url: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        JaMusicService.open(context, url)
    }
    Column(modifier) {
        if (!state.running) {
            Text("Music • Deezer", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Spacer(Modifier.height(12.dp))
            Text("A Deezer account is required. Playback availability depends on your plan and region.",
                color = Color.White)
            Text("Sign in directly on Deezer. JA does not collect your password.",
                color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { open(JaMusicService.LOGIN) }) { Text("Sign in to Deezer") }
            TextButton(onClick = { open(JaMusicService.LOGIN) }) {
                Text("account.deezer.com/en-us/login/", color = Color(0xFFE5A43B))
            }
            Button(onClick = { open(JaMusicService.HOME) }) { Text("Open Deezer") }
            Text("Music can continue while browsing or outside JA. Starting a video pauses it. Use Stop Music to end the session.",
                color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
        } else {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { JaMusicService.back() }, enabled = state.canGoBack) { Text("Back") }
                Button(onClick = { open(JaMusicService.HOME) }) { Text("Home") }
                Button(onClick = { open(JaMusicService.LOGIN) }) { Text("Sign in") }
                Button(onClick = { JaMusicService.reload() }) { Text("Reload") }
                Button(onClick = { JaMusicService.playPause() }) { Text(if (state.playing) "Pause" else "Play") }
                Button(onClick = { JaMusicService.stop(context) }) { Text("Stop Music") }
                TextButton(onClick = { confirmReset = true }) { Text("Reset login") }
            }
            Text("Deezer • ${state.host.ifBlank { "Preparing…" }}",
                color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
            state.message?.let { Text(it, color = Color(0xFFFFC67D), style = MaterialTheme.typography.bodySmall) }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            val session = state.session
            if (session != null) {
                AndroidView(
                    factory = { GeckoView(it) },
                    update = { view ->
                        if (view.session !== session) {
                            if (view.session != null) view.releaseSession()
                            view.setSession(session)
                        }
                    },
                    onRelease = { view -> view.releaseSession() },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
            }
        }
    }
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("Reset Deezer login?") },
        text = { Text("This stops music and clears only Deezer's isolated Music browser data. Sign in again afterward.") },
        confirmButton = { TextButton(onClick = {
            confirmReset = false; JaMusicService.resetLogin()
        }) { Text("Reset") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } }
    )
}
