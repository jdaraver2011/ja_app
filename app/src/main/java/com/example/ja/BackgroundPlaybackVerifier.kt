package com.example.ja

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
 * Bounded, serial off-screen GeckoSession checks. No hidden GeckoView is
 * displayed to the user. A real advancing <video> emits a TENTATIVE signal;
 * a preroll ad may also advance, so only foreground playback is persisted as
 * a working server for a TV season or movie.
 *
 * Headless/off-screen rendering differs across devices/providers. An absent
 * signal never makes a server fail or removes it from the fallback chain.
 */
class BackgroundPlaybackVerifier(context: Context) {
    private val application = context.applicationContext
    private val observed = mutableMapOf<String, LinkedHashSet<String>>()
    private var job: Job? = null
    private var activeKey: String? = null
    private val perServerMs = 7500L
    private var cachedExtension: WebExtension? = null

    private fun key(r: PlaybackRequest) = "${r.media.type}:${r.media.id}:${r.season}:${r.episode}"
    private fun url(choice: PlaybackChoice) = when (val source = choice.source) {
        is PlaybackSource.Embed -> source.url
        is PlaybackSource.Native -> source.url
    }

    fun stop() {
        job?.cancel()
        job = null
        activeKey = null
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

    fun start(
        scope: CoroutineScope,
        request: PlaybackRequest,
        candidates: List<PlaybackChoice>,
        onProgress: (checked: Int, total: Int, observed: Int) -> Unit = { _, _, _ -> }
    ) {
        val nextKey = key(request)
        if (job?.isActive == true && activeKey == nextKey) return
        stop()
        activeKey = nextKey
        val candidatesToCheck = candidates
            .filter { it.source is PlaybackSource.Embed }
            .distinctBy { url(it) }
        // One session at a time: never launch 15 audio/video decoders in the
        // background and never compete with the foreground video player.
        job = scope.launch(Dispatchers.Main.immediate) {
            val total = candidatesToCheck.size
            try {
                for ((index, choice) in candidatesToCheck.withIndex()) {
                    val started = withTimeoutOrNull(perServerMs) {
                        try { probeOne(choice) } catch (_: Exception) { false }
                    } == true
                    if (started) {
                        observed.getOrPut(nextKey) { LinkedHashSet() }.add(choice.id)
                        Log.i("JA_VERIFY", "BACKGROUND_VIDEO_STARTED id=${choice.id} " +
                            "tmdb=${request.media.id} season=${request.season} episode=${request.episode}")
                    } else {
                        Log.d("JA_VERIFY", "BACKGROUND_NO_SIGNAL id=${choice.id}")
                    }
                    onProgress(index + 1, total, observedFor(request).size)
                }
            } catch (cancel: CancellationException) {
                Log.i("JA_VERIFY", "BACKGROUND_SCAN_CANCELLED key=$nextKey")
                throw cancel
            } finally {
                if (activeKey == nextKey) activeKey = null
            }
        }
    }

    private suspend fun probeOne(choice: PlaybackChoice): Boolean = withContext(Dispatchers.Main.immediate) {
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
            // Explicitly inactive: this is an offscreen, non-interactive probe.
            // Some GeckoView versions suspend hidden playback; that produces an
            // inconclusive NO_SIGNAL rather than a false provider failure.
            try { session.setActive(false) } catch (_: Exception) {}
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
                    GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE,
                    GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE ->
                        GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
                    else -> null
                }
            }
            try {
                val runtime = GeckoRuntime.getDefault(application)
                fun withExtension(ext: WebExtension?) {
                        if (finished) return
                        if (ext == null) { finish(false); return }
                        try {
                            session.webExtensionController.setMessageDelegate(
                                ext, object : WebExtension.MessageDelegate {
                                    override fun onConnect(port: WebExtension.Port) {
                                        if (finished) return
                                        port.setDelegate(object : WebExtension.PortDelegate {
                                            override fun onPortMessage(message: Any, port: WebExtension.Port) {
                                                val json = message as? JSONObject ?: return
                                                if (json.optString("event") == "VIDEO_STARTED") {
                                                    Log.i("JA_VERIFY", "PROBE_MEDIA_ADVANCING id=${choice.id} " +
                                                        "frame=${json.optString("frame").take(80)}")
                                                    finish(true)
                                                }
                                            }
                                        })
                                        // A globally installed built-in content script
                                        // must NEVER mute foreground video. Only this
                                        // detached session receives the ARM message.
                                        try { port.postMessage(JSONObject().put("action", "ARM_PROBE")) }
                                        catch (_: Exception) {}
                                    }
                                }, "ja_background_probe"
                            )
                            session.open(runtime)
                            session.loadUri(url(choice))
                        } catch (ex: Exception) {
                            Log.w("JA_VERIFY", "PROBE_START_FAILED id=${choice.id}", ex)
                            finish(false)
                        }
                }
                val ready = cachedExtension
                if (ready != null) withExtension(ready)
                else runtime.webExtensionController
                    .installBuiltIn("resource://android/assets/ja_background_probe/")
                    .accept({ ext ->
                        if (ext != null) cachedExtension = ext
                        withExtension(ext)
                    }, { error ->
                        Log.w("JA_VERIFY", "PROBE_EXTENSION_FAILED id=${choice.id}", error)
                        finish(false)
                    })
            } catch (ex: Exception) {
                Log.w("JA_VERIFY", "PROBE_GECKO_UNAVAILABLE id=${choice.id}", ex)
                finish(false)
            }
        }
    }
}
