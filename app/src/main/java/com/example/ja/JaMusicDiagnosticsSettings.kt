package com.example.ja

import android.util.Log
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.example.ja.experiments.music.JaMusicExperimentChecks

@Composable
internal fun JaMusicDiagnosticsSettings() {
    val context = LocalContext.current
    val music by JaMusicService.state.collectAsState()
    var result by remember { mutableStateOf<String?>(null) }
    if (music.running) {
        TvFocusButton(onClick = { JaMusicService.stop(context) }) { Text("Stop Music") }
    }
    Text("Experimental Music checks", style = MaterialTheme.typography.titleMedium, color = Color.White)
    Text("Runs simulated source handoff, resume and interruption checks. No Deezer login, network requests or audio playback.",
        color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
    TvFocusButton(onClick = {
        Log.i("JA_MUSIC_NATIVE", "DEVICE_CHECK version=${BuildConfig.VERSION_NAME} mode=SIMULATED")
        result = try {
            JaMusicExperimentChecks.run { line -> Log.i("JA_MUSIC_NATIVE", line) }
        } catch (failure: AssertionError) {
            Log.e("JA_MUSIC_NATIVE", "RUN_COMPLETE mode=SIMULATED result=FAIL", failure)
            "A simulated Music check failed. Capture JA_MUSIC_NATIVE in ADB."
        } catch (failure: RuntimeException) {
            Log.e("JA_MUSIC_NATIVE", "RUN_COMPLETE mode=SIMULATED result=ERROR", failure)
            "Music checks could not finish. Capture JA_MUSIC_NATIVE in ADB."
        }
    }) { Text("Run simulated Music checks") }
    result?.let { Text(it, color = Color.LightGray, style = MaterialTheme.typography.bodySmall) }
}
