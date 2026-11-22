package com.example.ja

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private data class JaAuthCallback(val type: String, val accessToken: String, val refreshToken: String, val error: String = "")

private fun parseJaAuthCallback(raw: String): JaAuthCallback? = runCatching {
    val uri = Uri.parse(raw)
    require(uri.scheme == "ja" && uri.host == "auth" && uri.path == "/callback")
    val fragment = uri.fragment.orEmpty()
    val values = Uri.parse("https://callback.invalid/?${fragment}")
    fun value(key: String) = uri.getQueryParameter(key) ?: values.getQueryParameter(key).orEmpty()
    JaAuthCallback(
        type = value("type"),
        accessToken = value("access_token"),
        refreshToken = value("refresh_token"),
        error = value("error_description").ifBlank { value("error") }
    )
}.getOrNull()

@Composable
internal fun JaAccountSyncSettings(
    dao: WatchDao,
    modifier: Modifier = Modifier,
    authCallbackUri: String? = null,
    showIntro: Boolean = true,
    onConsumeAuthCallback: () -> Unit = {},
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
    var confirmPassword by remember { mutableStateOf("") }
    var recoveryToken by remember { mutableStateOf<Pair<String, String>?>(null) }
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
    LaunchedEffect(authCallbackUri) {
        val raw = authCallbackUri ?: return@LaunchedEffect
        val callback = parseJaAuthCallback(raw)
        if (callback == null) {
            message = "This link is not a valid JA account link. Request a new email from JA."
        } else if (callback.error.isNotBlank()) {
            message = "Supabase could not complete the link: ${callback.error.replace('+', ' ')}"
        } else if (callback.accessToken.isBlank() || callback.refreshToken.isBlank()) {
            message = "The email link did not include a JA sign-in token. Check Supabase's email template and request a new link."
        } else if (callback.type == "recovery") {
            recoveryToken = callback.accessToken to callback.refreshToken
            message = "Email verified for password recovery. Enter a new password below."
        } else {
            message = runCatching { account.acceptEmailConfirmation(callback.accessToken, callback.refreshToken) }
                .getOrElse { it.message ?: "Could not verify the email." }
            session = account.session()
            if (session != null) onAuthenticated()
        }
        onConsumeAuthCallback()
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showIntro) {
            Text("JA account sync", fontWeight = FontWeight.SemiBold)
            Text("Sign in with email and password to sync Movies/TV watch history and favorites, JA Music saved items and playlists, and IPTV channel favorites and order. JA syncs on sign-in and when the app opens; tap Sync now after changes if you want them uploaded immediately. Playback/display settings, provider credentials, and temporary caches stay on this device.", color = Color(0xFFCBD5DE))
        }
        if (session == null) {
            OutlinedTextField(value = email, onValueChange = { email = it.trim() }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Email") }, singleLine = true)
            if (recoveryToken != null) {
                OutlinedTextField(value = password, onValueChange = { password = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("New password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(value = confirmPassword, onValueChange = { confirmPassword = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Confirm new password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                Button(enabled = !busy && password.length >= 6 && password == confirmPassword, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE5A43B), contentColor = Color(0xFF101820)), onClick = {
                    val tokens = recoveryToken ?: return@Button
                    runTask { account.completePasswordReset(tokens.first, tokens.second, password).also { password = ""; confirmPassword = ""; recoveryToken = null } }
                }) { Text("Set password") }
            } else {
                OutlinedTextField(value = password, onValueChange = { password = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy && email.contains("@") && password.length >= 6, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE5A43B), contentColor = Color(0xFF101820)), onClick = {
                        runTask { account.signUp(email, password).also { password = "" } }
                    }) { Text("Create account") }
                    Button(enabled = !busy && email.contains("@") && password.isNotEmpty(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE5A43B), contentColor = Color(0xFF101820)), onClick = {
                        runTask { account.signIn(email, password); password = ""; session = account.session(); account.sync() }
                    }) { Text("Sign in") }
                }
                Button(enabled = !busy && email.contains("@"), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A4052), contentColor = Color.White), onClick = {
                    runTask { account.sendPasswordReset(email) }
                }) { Text("Forgot password?") }
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
internal fun JaRequiredAccountGate(
    dao: WatchDao,
    authCallbackUri: String? = null,
    onConsumeAuthCallback: () -> Unit = {},
    onTestUnlock: () -> Unit = {},
    onAuthenticated: () -> Unit
) {
    var logoTapCount by remember { mutableStateOf(0) }
    var showUnlockPrompt by remember { mutableStateOf(false) }
    var unlockCode by remember { mutableStateOf("") }
    var unlockError by remember { mutableStateOf("") }
    Surface(Modifier.fillMaxSize(), color = Color(0xFF101820), contentColor = Color.White) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
            Card(Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2A37), contentColor = Color.White)) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Image(
                        painter = painterResource(R.mipmap.ic_launcher_foreground),
                        contentDescription = "JA app logo",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(72.dp).clickable {
                            logoTapCount += 1
                            if (logoTapCount >= 7) {
                                showUnlockPrompt = true
                                unlockCode = ""
                                unlockError = ""
                                logoTapCount = 0
                            }
                        }
                    )
                    Text("Welcome", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
                    Text("Sign in or create an account to continue. Your saved data can sync across your devices.", color = Color(0xFFCBD5DE), fontSize = 16.sp)
                    JaAccountSyncSettings(dao, authCallbackUri = authCallbackUri, showIntro = false,
                        onConsumeAuthCallback = onConsumeAuthCallback, onAuthenticated = onAuthenticated)
                }
            }
        }
        if (showUnlockPrompt) {
            AlertDialog(
                onDismissRequest = { showUnlockPrompt = false },
                title = { Text("Test access") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Enter the test unlock code to continue without signing in.")
                        OutlinedTextField(value = unlockCode, onValueChange = { unlockCode = it; unlockError = "" },
                            label = { Text("Unlock code") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                        if (unlockError.isNotBlank()) Text(unlockError, color = Color(0xFFFF8A80))
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (unlockCode == "192168") {
                            showUnlockPrompt = false
                            onTestUnlock()
                        } else unlockError = "That code is not correct."
                    }) { Text("Unlock", color = Color(0xFFE5A43B)) }
                },
                dismissButton = { TextButton(onClick = { showUnlockPrompt = false }) { Text("Cancel") } }
            )
        }
    }
}
