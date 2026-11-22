package com.example.ja

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun JaAccountSyncSettings(
    dao: WatchDao,
    modifier: Modifier = Modifier,
    onCloudDataApplied: () -> Unit = {},
    onAuthenticated: () -> Unit = {},
    onSignedOut: () -> Unit = {}
) {
    val context = LocalContext.current.applicationContext
    val account = remember { JaAccountSync(context, dao) }
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf(account.session()) }
    var email by remember { mutableStateOf(session?.email.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    fun runTask(task: suspend () -> String) {
        if (busy) return
        busy = true
        message = "Working…"
        scope.launch {
            var completed = false
            message = try { task().also {
                completed = true
                if (it.startsWith("Downloaded")) onCloudDataApplied()
            } }
            catch (e: Exception) { e.message ?: "Account request failed." }
            session = account.session()
            busy = false
            if (completed) { if (session != null) onAuthenticated() else onSignedOut() }
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("JA account sync")
        Text("Sign in with email and password to sync Movies/TV watch history and favorites, JA Music saved items and playlists, and IPTV channel favorites and order. JA syncs on sign-in and when the app opens; tap Sync now after changes if you want them uploaded immediately. Playback/display settings, provider credentials, and temporary caches stay on this device.")
        if (session == null) {
            OutlinedTextField(value = email, onValueChange = { email = it.trim() }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Email") }, singleLine = true)
            OutlinedTextField(value = password, onValueChange = { password = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy && email.contains("@") && password.length >= 6, onClick = {
                    runTask { account.signUp(email, password).also { password = "" } }
                }) { Text("Create account") }
                Button(enabled = !busy && email.contains("@") && password.isNotEmpty(), onClick = {
                    runTask { account.signIn(email, password); password = ""; session = account.session(); account.sync() }
                }) { Text("Sign in") }
            }
        } else {
            Text("Signed in as ${session?.email}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = { runTask { account.sync() } }) { Text("Sync now") }
                Button(enabled = !busy, onClick = { runTask { account.signOut().let { "Signed out. This device's saved data remains here." } } }) { Text("Sign out") }
            }
            if (message.contains("Choose Download cloud data or Upload this device") || message.contains("Both this device and the cloud changed")) {
                Text("Pick which copy should replace the other for this first/conflicting sync.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy, onClick = { runTask { account.chooseSync("download") } }) { Text("Download cloud data") }
                    Button(enabled = !busy, onClick = { runTask { account.chooseSync("upload") } }) { Text("Upload this device") }
                }
            }
        }
        if (message.isNotBlank()) Text(message)
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
internal fun JaRequiredAccountGate(dao: WatchDao, onAuthenticated: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Welcome to JA")
        Text("Sign in or create a JA account to continue. Your saved personal data can then sync across your devices.")
        JaAccountSyncSettings(dao, onAuthenticated = onAuthenticated)
    }
}
