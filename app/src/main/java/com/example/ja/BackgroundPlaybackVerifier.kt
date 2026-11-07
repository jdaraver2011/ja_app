package com.example.ja

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import kotlin.coroutines.resume

/**
 * Bounded, concurrent off-screen GeckoSession checks. No hidden GeckoView is
 * displayed to the user. A real advancing <video> emits a TENTATIVE signal;
 * a preroll ad may also advance, so only foreground playback is persisted as
 * a working server for a TV season or movie.
 *
 * Headless/off-screen rendering differs across devices/providers. An absent
 * signal never makes a server fail or removes it from the fallback chain.
 */
class BackgroundPlaybackVerifier(context: Context) {
    private val application = context.applicationContext
    /**
     * Detaching 16 simultaneous GeckoSessions is not a reliable video check
     * on low-power Android TV devices. It can exhaust graphics/media resources
     * even if every coroutine has finished. Keep all HTTP probes concurrent,
     * but do not open hidden video sessions on television hardware.
     *
     * Handheld phones retain parallel headless video verification. The
     * foreground player always has priority over tentative video signals.
     */
    val tvSafeMode: Boolean = run {
        val uiMode = application.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        uiMode == Configuration.UI_MODE_TYPE_TELEVISION ||
            application.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            application.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
    }
    private val observed = mutableMapOf<String, LinkedHashSet<String>>()
    private var job: Job? = null
    private var activeKey: String? = null
    private val perServerMs = 7500L
    private var cachedExtension: WebExtension? = null
    private var generation = 0L

    private fun key(r: PlaybackRequest) = "${r.media.type}:${r.media.id}:${r.season}:${r.episode}"
    private fun url(choice: PlaybackChoice) = when (val source = choice.source) {
        is PlaybackSource.Embed -> source.url
        is PlaybackSource.Native -> source.url
    }

    /** Cancel every detached probe. Return its supervising Job so callers
     * can await complete GeckoSession cleanup before foreground playback.
     * The parallel scans on the details page are otherwise unchanged.
     */
    fun stop(): Job? {
        generation++
        val previous = job
        val wasRunning = previous?.isActive == true
        previous?.cancel()
        job = null
        activeKey = null
        if (wasRunning) {
            Log.i("JA_PERF", "BACKGROUND_PROBES_CANCEL_REQUESTED")
        }
        return previous
    }

    fun observedFor(request: PlaybackRequest): Set<String> =
        observed[key(request)]?.toSet() ?: emptySet()

    fun invalidate(request: PlaybackRequest, providerId: String) {
        observed[key(request)]?.remove(providerId)
    }

    fun prioritize(request: PlaybackRequest, choices: List<PlaybackChoice>): List<PlaybackChoice> {
        val successes = observedFor(request)
        if (successes.isEmpty()) return choices
        return choices.withIndex().sortedWith(
            compareBy<IndexedValue<PlaybackChoice>> { if (it.value.id in successes) 0 else 1 }
                .thenBy { it.index }
        ).map { it.value }
    }

    /** Launch all eligible embed candidates concurrently after installing the
     * built-in probe extension ONCE. No server waits for another server's probe.
     * Each GeckoSession gets its own timeout and closes on cancellation.
     * This can use significant memory on low-end Android TV hardware; the
     * checks stop immediately when foreground playback begins.
     */
    fun start(
        scope: CoroutineScope,
        request: PlaybackRequest,
        candidates: List<PlaybackChoice>,
        onProgress: (checked: Int, total: Int, observed: Int) -> Unit = { _, _, _ -> }
    ) {
        if (tvSafeMode) {
            Log.i("JA_VERIFY", "TV_SAFE_HEADLESS_SKIPPED count=${candidates.count { it.source is PlaybackSource.Embed }} " +
                "key=${key(request)} reason=protect_foreground_playback")
            onProgress(0, 0, 0)
            return
        }
        val nextKey = key(request)
        if (job?.isActive == true && activeKey == nextKey) return
        stop()
        activeKey = nextKey
        val scanGeneration = generation
        val candidatesToCheck = candidates
            .filter { it.source is PlaybackSource.Embed }
            .distinctBy { url(it) }
        job = scope.launch(Dispatchers.Main.immediate) {
            val total = candidatesToCheck.size
            var checked = 0
            try {
                if (total == 0) {
                    onProgress(0, 0, observedFor(request).size)
                    return@launch
                }
                onProgress(0, total, observedFor(request).size)
                // The same add-on is shared by ALL sessions. Installing it once
                // avoids simultaneous GeckoView installation races.
                val extension = withTimeoutOrNull(perServerMs) { getProbeExtension() }
                if (extension == null) {
                    Log.w("JA_VERIFY", "PROBE_EXTENSION_UNAVAILABLE key=$nextKey")
                    if (generation == scanGeneration) onProgress(0, total, observedFor(request).size)
                    return@launch
                }
                Log.i("JA_VERIFY", "BACKGROUND_PARALLEL_START count=$total key=$nextKey")
                supervisorScope {
                    candidatesToCheck.map { choice ->
                        async(Dispatchers.Main.immediate) {
                            val started = withTimeoutOrNull(perServerMs) {
                                try { probeOne(choice, extension) }
                                catch (cancel: CancellationException) { throw cancel }
                                catch (ex: Exception) {
                                    Log.w("JA_VERIFY", "PROBE_FAILED id=${choice.id}", ex)
                                    false
                                }
                            } == true
                            // All result updates take place on the main thread.
                            // Ignore stale results from a cancelled/replaced scan.
                            if (generation == scanGeneration) {
                                if (started) {
                                    observed.getOrPut(nextKey) { LinkedHashSet() }.add(choice.id)
                                    Log.i("JA_VERIFY", "BACKGROUND_VIDEO_STARTED id=${choice.id} " +
                                        "tmdb=${request.media.id} season=${request.season} episode=${request.episode}")
                                } else {
                                    Log.d("JA_VERIFY", "BACKGROUND_NO_SIGNAL id=${choice.id}")
                                }
                                checked++
                                onProgress(checked, total, observedFor(request).size)
                            }
                        }
                    }.awaitAll()
                }
                Log.i("JA_VERIFY", "BACKGROUND_PARALLEL_DONE checked=$checked/$total signals=${observedFor(request).size}")
            } catch (cancel: CancellationException) {
                Log.i("JA_VERIFY", "BACKGROUND_SCAN_CANCELLED key=$nextKey")
                throw cancel
            } finally {
                if (generation == scanGeneration) activeKey = null
            }
        }
    }

    private suspend fun getProbeExtension(): WebExtension? =
        withContext(Dispatchers.Main.immediate) {
            cachedExtension?.let { return@withContext it }
            suspendCancellableCoroutine<WebExtension?> { continuation ->
                try {
                    val runtime = GeckoRuntime.getDefault(application)
                    runtime.webExtensionController
                        .installBuiltIn("resource://android/assets/ja_background_probe/")
                        .accept({ extension ->
                            if (extension != null) cachedExtension = extension
                            if (continuation.isActive) continuation.resume(extension)
                        }, { error ->
                            Log.w("JA_VERIFY", "PROBE_EXTENSION_FAILED: ${error?.message}")
                            if (continuation.isActive) continuation.resume(null)
                        })
                } catch (ex: Exception) {
                    Log.w("JA_VERIFY", "PROBE_EXTENSION_UNAVAILABLE", ex)
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }

    private suspend fun probeOne(choice: PlaybackChoice, extension: WebExtension): Boolean = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            val session = GeckoSession()
            var finished = false
            fun finish(value: Boolean) {
                if (finished) return
                finished = true
                try { session.close() } catch (_: Exception) {}
                if (continuation.isActive) continuation.resume(value)
            }
            continuation.invokeOnCancellation {
                if (!finished) {
                    finished = true
                    try { session.close() } catch (_: Exception) {}
                }
            }
            // No GeckoView is attached. The session becomes active only after
            // GeckoSession.open(); otherwise GeckoView may silently ignore it.
            session.promptDelegate = object : GeckoSession.PromptDelegate {
                override fun onAlertPrompt(s: GeckoSession, p: GeckoSession.PromptDelegate.AlertPrompt):
                    GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? = GeckoResult.fromValue(p.dismiss())
                override fun onButtonPrompt(s: GeckoSession, p: GeckoSession.PromptDelegate.ButtonPrompt):
                    GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? = GeckoResult.fromValue(p.dismiss())
                override fun onTextPrompt(s: GeckoSession, p: GeckoSession.PromptDelegate.TextPrompt):
                    GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? = GeckoResult.fromValue(p.dismiss())
                override fun onPopupPrompt(s: GeckoSession, p: GeckoSession.PromptDelegate.PopupPrompt):
                    GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? = GeckoResult.fromValue(p.confirm(AllowOrDeny.DENY))
            }
            session.navigationDelegate = object : GeckoSession.NavigationDelegate {
                override fun onNewSession(s: GeckoSession, uri: String): GeckoResult<GeckoSession>? =
                    GeckoResult.fromValue(null)
                override fun onLoadRequest(s: GeckoSession, r: GeckoSession.NavigationDelegate.LoadRequest):
                    GeckoResult<AllowOrDeny>? {
                    val expectedHost = Uri.parse(url(choice)).host
                    val targetHost = Uri.parse(r.uri).host
                    // Block unwanted new-domain top-level navigation; this is
                    // not a redirect migration engine (see ServerDomainLedger).
                    if (!targetHost.equals(expectedHost, ignoreCase = true))
                        return GeckoResult.fromValue(AllowOrDeny.DENY)
                    return null
                }
            }
            session.permissionDelegate = object : GeckoSession.PermissionDelegate {
                override fun onContentPermissionRequest(
                    s: GeckoSession, permission: GeckoSession.PermissionDelegate.ContentPermission
                ): GeckoResult<Int>? = when (permission.permission) {
                    // Never grant audible autoplay to hidden, simultaneous
                    // player sessions. Only muted/in-audible video is probed.
                    GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE ->
                        GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
                    GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE ->
                        GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
                    else -> null
                }
            }
            try {
                val runtime = GeckoRuntime.getDefault(application)
                session.webExtensionController.setMessageDelegate(
                    extension, object : WebExtension.MessageDelegate {
                        override fun onConnect(port: WebExtension.Port) {
                            if (finished) return
                            port.setDelegate(object : WebExtension.PortDelegate {
                                override fun onPortMessage(message: Any, port: WebExtension.Port) {
                                    if (finished) return
                                    val json = message as? JSONObject ?: return
                                    if (json.optString("event") == "VIDEO_STARTED") {
                                        Log.i("JA_VERIFY", "PROBE_MEDIA_ADVANCING id=${choice.id} " +
                                            "frame=${json.optString("frame").take(80)}")
                                        finish(true)
                                    }
                                }
                            })
                            // This authorization reaches ONLY the detached
                            // session; the visible player is never armed/muted.
                            try { port.postMessage(JSONObject().put("action", "ARM_PROBE")) }
                            catch (_: Exception) {}
                        }
                    }, "ja_background_probe"
                )
                session.open(runtime)
                try { session.setActive(true) } catch (_: Exception) {}
                session.loadUri(url(choice))
            } catch (ex: Exception) {
                Log.w("JA_VERIFY", "PROBE_START_FAILED id=${choice.id}", ex)
                finish(false)
            }
        }
    }
}
