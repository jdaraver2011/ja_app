package com.example.ja

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun JaMusicLabScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by JaMusicLabService.state.collectAsState()
    var arl by remember { mutableStateOf("") }
    var track by remember { mutableStateOf("") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun notifications() {
        if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    fun time(ms: Long) = "%02d:%02d".format(ms / 60000, (ms / 1000) % 60)
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Music Lab • 2.4.5musictest", style = MaterialTheme.typography.titleLarge, color = Color.White)
        Text("Account and full-track source test. This first milestone does not include encrypted-audio decoding or automatic password login.", color = Color.LightGray)
        OutlinedTextField(value = arl, onValueChange = { arl = it }, label = { Text("Deezer ARL session") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { notifications(); JaMusicLabService.connect(context, arl); arl = "" }, enabled = arl.isNotBlank() && !state.busy) { Text("Connect") }
            Button(onClick = { notifications(); JaMusicLabService.restore(context) }, enabled = !state.busy) { Text("Check saved session") }
            TextButton(onClick = { arl = ""; JaMusicLabService.logout(context) }) { Text("Sign out / clear cache") }
        }
        if (state.account.isNotBlank()) Text("Account: ${state.account}", color = Color.White)
        OutlinedTextField(value = track, onValueChange = { track = it }, label = { Text("Deezer track ID or track link") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { notifications(); JaMusicLabService.playTrack(context, track) }, enabled = track.isNotBlank() && !state.busy) { Text("Test full-track playback") }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(state.message, color = Color(0xFFFFD078))
        if (state.title.isNotBlank()) Text(state.title, color = Color.White)
        Text("${time(state.position)} / ${time(state.duration)}", color = Color.White)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { JaMusicLabService.seek(-10000) }, enabled = state.duration > 0) { Text("−10s") }
            Button(onClick = { JaMusicLabService.playPause() }, enabled = state.duration > 0) { Text(if (state.playing) "Pause" else "Play") }
            Button(onClick = { JaMusicLabService.seek(10000) }, enabled = state.duration > 0) { Text("+10s") }
            Button(onClick = { JaMusicLabService.stop(context) }, enabled = state.running) { Text("Stop") }
        }
        Text("Session is encrypted on this device. Temporary audio cache is limited to 64 MB. Diagnostic tag: JA_MUSIC_LAB. Never share your ARL.", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
    }
}
