package com.example.ja

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal data class JaUpdateState(val message: String = "", val busy: Boolean = false, val ready: Boolean = false)

internal class JaAppUpdates(private val context: Context) {
    companion object { private val running = java.util.concurrent.atomic.AtomicBoolean(false) }
    private val mutable = MutableStateFlow(JaUpdateState())
    val state = mutable.asStateFlow()
    private val client = OkHttpClient.Builder().followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val folder = File(context.cacheDir, "updates")
    private val apk get() = File(folder, "JA-update.apk")
    private var verified = false
    private fun request(url: String) = Request.Builder().url(url).header("User-Agent", "JA/${BuildConfig.VERSION_NAME}").build()
    private fun https(url: String): String {
        require(Uri.parse(url).scheme == "https") { "Update downloads require HTTPS." }
        return url
    }
    private fun json(url: String): JSONObject = client.newCall(request(https(url))).execute().use { response ->
        check(response.isSuccessful) {
            if(response.code == 404) "No public update release is available. Check the update repository setup."
            else "Update server returned HTTP ${response.code}. Try again later."
        }
        val bytes = java.io.ByteArrayOutputStream()
        (response.body?.byteStream() ?: error("Empty update response.")).use { input ->
            val buffer = ByteArray(8192)
            while(true) {
                val count = input.read(buffer)
                if(count < 0) break
                check(bytes.size() + count <= 1024 * 1024) { "Update metadata is too large." }
                bytes.write(buffer, 0, count)
            }
        }
        JSONObject(bytes.toString("UTF-8"))
    }
    suspend fun updateNow() = withContext(Dispatchers.IO) {
        if(!running.compareAndSet(false, true)) {
            mutable.value = JaUpdateState("Another update check is finishing. Try again shortly.")
            return@withContext
        }
        mutable.value = JaUpdateState("Checking for updates…", busy = true)
        verified = false
        val partial = File(folder, "update.part")
        try {
            check(folder.isDirectory || folder.mkdirs()) { "Could not prepare update storage." }
            val repo = BuildConfig.JA_UPDATE_REPOSITORY
            require(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]+").matches(repo)) { "Update repository is not configured correctly." }
            val release = json("https://api.github.com/repos/$repo/releases/latest")
            val assets = release.getJSONArray("assets")
            var metadataUrl: String? = null
            for(i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if(asset.optString("name") == "ja-update.json") metadataUrl = asset.getString("browser_download_url")
            }
            val meta = json(metadataUrl ?: error("The latest release has no JA update metadata."))
            check(meta.getString("packageName") == context.packageName) { "Update package does not match JA." }
            val version = meta.getLong("versionCode")
            if(version <= BuildConfig.VERSION_CODE) {
                apk.delete()
                mutable.value = JaUpdateState("JA is up to date (${BuildConfig.VERSION_NAME}).")
                return@withContext
            }
            val expectedHash = meta.getString("sha256").lowercase()
            require(Regex("[0-9a-f]{64}").matches(expectedHash)) { "Invalid update checksum." }
            val size = meta.getLong("size")
            require(size in 1..(1024L * 1024 * 1024)) { "Invalid update size." }
            check(folder.usableSpace > size * 2 + 64L * 1024 * 1024) { "Not enough free space for the update." }
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            client.newCall(request(https(meta.getString("apkUrl")))).execute().use { response ->
                check(response.isSuccessful) { "APK download failed: HTTP ${response.code}." }
                val input = response.body?.byteStream() ?: error("Empty APK download.")
                input.use { source -> partial.outputStream().use { target ->
                    val buffer = ByteArray(64 * 1024)
                    var reported = -1
                    while(true) {
                        coroutineContext.ensureActive()
                        val count = source.read(buffer)
                        if(count < 0) break
                        total += count
                        check(total <= size) { "APK size differs from its update metadata." }
                        digest.update(buffer, 0, count);target.write(buffer, 0, count)
                        val percent = (total * 100 / size).toInt()
                        if(percent != reported) {
                            reported = percent
                            mutable.value = JaUpdateState("Downloading JA ${meta.getString("versionName")}… $percent%", busy = true)
                        }
                    }
                } }
            }
            check(JaUpdateRules.downloadMatches(expectedHash, hex(digest.digest()), size, total)) { "Update verification failed. Please retry." }
            validatePackage(partial, version)
            apk.delete()
            check(partial.renameTo(apk)) { "Could not save the verified update." }
            verified = true
            mutable.value = JaUpdateState("JA ${meta.getString("versionName")} is ready. Select Install Update to continue.", ready = true)
        } catch(cancel: kotlinx.coroutines.CancellationException) {
            mutable.value = JaUpdateState("Update cancelled.");throw cancel
        } catch(error: Exception) {
            mutable.value = JaUpdateState(error.message ?: "Update failed. Please retry.")
        } finally { partial.delete();running.set(false) }
    }
    @Suppress("DEPRECATION")
    private fun validatePackage(file: File, version: Long) {
        val pm = context.packageManager
        val flags = if(Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val candidate = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: error("The downloaded file is not a valid APK.")
        val installed = pm.getPackageInfo(context.packageName, flags)
        val code = if(Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else candidate.versionCode.toLong()
        check(JaUpdateRules.versionMatches(context.packageName, candidate.packageName, BuildConfig.VERSION_CODE.toLong(), version, code)) { "APK package/version verification failed." }
        val own = signatures(installed)
        check(JaUpdateRules.signersMatch(own, signatures(candidate))) {
            "This update uses a different signing key. Publish APKs signed with the key used by the installed app."
        }
    }
    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo): Set<String> {
        val values = if(Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return values?.map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }?.toSet() ?: emptySet()
    }
    fun install(activity: Context) {
        try {
            check(verified && apk.isFile) { "Check and download the update first." }
            if(!context.packageManager.canRequestPackageInstalls()) {
                mutable.value = mutable.value.copy(message = "Allow JA to install updates, then return and select Install Update again.")
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                return
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
            activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch(error: Exception) {
            if(!apk.isFile) {verified=false;mutable.value=JaUpdateState("The cached update is no longer available. Select Update Now to download again.")}
            else mutable.value = mutable.value.copy(message = error.message ?: "Could not open the installer.")
        }
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
