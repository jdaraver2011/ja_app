package com.example.ja

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mozilla.geckoview.*
import java.util.WeakHashMap

/** Installed/enabled, Gecko startup, and filter health are different states. */
internal object JaGeckoAdBlockers {
    const val OFF = "off"
    const val UBLOCK = "ublock"
    const val ADGUARD = "adguard"
    private const val TAG = "JA_ADBLOCK"
    private const val UBLOCK_ID = "uBlock0@raymondhill.net"
    private const val ADGUARD_ID = "adguardadblocker@adguard.com"
    private const val UBLOCK_URL = "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi"
    private const val ADGUARD_URL = "https://addons.mozilla.org/firefox/downloads/latest/adguard-adblocker/latest.xpi"

    enum class Phase { IDLE, STARTING, ENABLED, OFF, FAILED, WARNING }
    data class Status(
        val mode: String = OFF,
        val phase: Phase = Phase.IDLE,
        val message: String = "Player blocker has not been prepared.",
        val version: String? = null
    )
    private val mutableStatus = MutableStateFlow(Status())
    val status: StateFlow<Status> = mutableStatus.asStateFlow()
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private class RuntimeState {
        val serial = Mutex()
        val lifecycle = JaBlockerLifecycleState()
        val changes = MutableStateFlow(0L)
        val readyExtensions = mutableMapOf<String, WebExtension>()
        var processUnavailable = false
    }
    private val states = WeakHashMap<GeckoRuntime, RuntimeState>()
    private fun known(id: String) = id == UBLOCK_ID || id == ADGUARD_ID
    fun normalized(mode: String): String = when(mode) { OFF, UBLOCK, ADGUARD -> mode; else -> UBLOCK }
    fun label(mode: String): String = when(normalized(mode)) { OFF -> "Off"; ADGUARD -> "AdGuard"; else -> "uBlock Origin" }
    private fun idFor(mode: String) = if (mode == UBLOCK) UBLOCK_ID else ADGUARD_ID

    private fun stateFor(runtime: GeckoRuntime): RuntimeState {
        states[runtime]?.let { return it }
        val state = RuntimeState()
        states[runtime] = state
        val controller = runtime.webExtensionController
        Log.i(TAG, "ENGINE_VERSION version=${BuildConfig.GECKOVIEW_VERSION} " +
            "runtime=${GeckoSession.getDefaultUserAgent()}")
        controller.setAddonManagerDelegate(object : WebExtensionController.AddonManagerDelegate {
            private fun changed(extension: WebExtension, event: String, stopped: Boolean = false) {
                if (!known(extension.id)) return
                if (stopped) {
                    state.lifecycle.stopped(extension.id)
                    state.readyExtensions.remove(extension.id)
                    val current = mutableStatus.value
                    if (current.mode != OFF && idFor(current.mode) == extension.id &&
                        current.phase == Phase.ENABLED) {
                        mutableStatus.value = current.copy(phase = Phase.WARNING,
                            message = "The selected extension stopped. Select a blocker again or choose Off.")
                    }
                }
                state.changes.value++
                Log.i(TAG, "LIFECYCLE event=$event id=${extension.id} version=${extension.metaData.version} " +
                    "enabled=${extension.metaData.enabled}")
            }
            override fun onEnabling(extension: WebExtension) {
                if (!known(extension.id)) return
                state.lifecycle.starting(extension.id)
                state.readyExtensions.remove(extension.id)
                changed(extension, "ENABLING")
            }
            override fun onEnabled(extension: WebExtension) {
                changed(extension, "ENABLED")
                // Reconcile a late native enable after a user selected Off.
                val desired = state.lifecycle.desiredMode()
                if (known(extension.id) && desired != null &&
                    (desired == OFF || extension.id != idFor(desired))) {
                    prepare(runtime, desired) { }
                }
            }
            override fun onReady(extension: WebExtension) {
                if (!known(extension.id)) return
                state.lifecycle.ready(extension.id)
                state.readyExtensions[extension.id] = extension
                changed(extension, "GECKO_READY")
                val desired = state.lifecycle.desiredMode()
                if (desired != null && extension.metaData.enabled &&
                    (desired == OFF || extension.id != idFor(desired))) {
                    prepare(runtime, desired) { }
                }
                // Do not say filter-ready: async filter loading may still fail.
            }
            override fun onDisabling(extension: WebExtension) {
                changed(extension, "DISABLING", true)
            }
            override fun onDisabled(extension: WebExtension) {
                changed(extension, "DISABLED", true)
            }
            override fun onUninstalled(extension: WebExtension) {
                changed(extension, "UNINSTALLED", true)
            }
            override fun onInstalled(extension: WebExtension) {
                changed(extension, "INSTALLED")
                val desired = state.lifecycle.desiredMode()
                if (known(extension.id) && desired != null && extension.metaData.enabled &&
                    (desired == OFF || extension.id != idFor(desired))) {
                    prepare(runtime, desired) { }
                }
            }
            override fun onInstallationFailed(extension: WebExtension?, installException: WebExtension.InstallException) {
                Log.w(TAG, "LIFECYCLE event=INSTALL_FAILED id=${extension?.id} code=${installException.code}")
            }
        })
        controller.setExtensionProcessDelegate(object : WebExtensionController.ExtensionProcessDelegate {
            override fun onDisabledProcessSpawning() {
                main.post {
                    state.processUnavailable = true
                    state.lifecycle.stopped(UBLOCK_ID)
                    state.lifecycle.stopped(ADGUARD_ID)
                    state.readyExtensions.clear()
                    state.changes.value++
                    val old = mutableStatus.value
                    if (old.mode != OFF) mutableStatus.value = old.copy(phase = Phase.FAILED,
                        message = "Gecko stopped the extension process. Restart JA before enabling a blocker again.")
                    Log.e(TAG, "EXTENSION_PROCESS_UNAVAILABLE restartRequired=true")
                }
            }
        })
        // GeckoView 160 uses typed permission responses, including the
        // data-collection decision. Do not retain the old 128 reflection shim.
        controller.promptDelegate = object : WebExtensionController.PromptDelegate {
            override fun onInstallPromptRequest(
                extension: WebExtension,
                permissions: Array<out String>,
                origins: Array<out String>,
                dataCollectionPermissions: Array<out String>
            ): GeckoResult<WebExtension.PermissionPromptResponse> {
                val approved = known(extension.id)
                Log.i(TAG, "ADDON_INSTALL_PERMISSION id=${extension.id} allow=$approved")
                return GeckoResult.fromValue(WebExtension.PermissionPromptResponse(approved, false, false))
            }
            override fun onOptionalPrompt(
                extension: WebExtension,
                permissions: Array<out String>,
                origins: Array<out String>,
                dataCollectionPermissions: Array<out String>
            ): GeckoResult<AllowOrDeny> {
                val approved = known(extension.id) && dataCollectionPermissions.isEmpty()
                Log.i(TAG, "ADDON_OPTIONAL_PERMISSION id=${extension.id} allow=$approved")
                return GeckoResult.fromValue(if (approved) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
            }
            override fun onUpdatePrompt(
                extension: WebExtension,
                newPermissions: Array<out String>,
                newOrigins: Array<out String>,
                newDataCollectionPermissions: Array<out String>
            ): GeckoResult<AllowOrDeny> {
                Log.w(TAG, "ADDON_UPDATE_PERMISSION_DENIED id=${extension.id}")
                return GeckoResult.fromValue(AllowOrDeny.DENY)
            }
        }

        return state
    }

    private suspend fun <T> awaitResult(result: GeckoResult<T>, cancelInstall: Boolean = false): T? {
        val completion = CompletableDeferred<T?>()
        result.accept({ completion.complete(it) }, {
            completion.completeExceptionally(it ?: IllegalStateException("Gecko operation failed"))
        })
        return try { completion.await() }
        catch (cancel: CancellationException) {
            if (cancelInstall) result.cancel()
            throw cancel
        }
    }

    private suspend fun started(state: RuntimeState, extension: WebExtension, token: Long, mode: String,
        allowRestoredMetadata: Boolean): WebExtension {
        // After restored extensions have already started, list() may be our
        // first observation. A resolved base URL is Gecko startup metadata.
        if (allowRestoredMetadata && extension.metaData.baseUrl.orEmpty().startsWith("moz-extension://") &&
            !extension.metaData.optionsPageUrl.isNullOrBlank() && extension.metaData.enabled) {
            state.lifecycle.ready(extension.id)
            state.readyExtensions[extension.id] = extension
        }
        withTimeout(30_000L) {
            state.changes.first {
                state.lifecycle.hasStarted(extension.id) || state.processUnavailable || !state.lifecycle.isCurrent(token, mode)
            }
        }
        check(!state.processUnavailable) { "Extension process stopped; restart JA" }
        check(state.lifecycle.isCurrent(token, mode)) { "Selection changed during extension startup" }
        return state.readyExtensions[extension.id] ?: extension
    }

    /** Every call re-lists native state; there is no cached Active shortcut. */
    fun prepare(runtime: GeckoRuntime, requestedMode: String, done: (String) -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { prepare(runtime, requestedMode, done) }; return
        }
        val mode = normalized(requestedMode)
        val state = stateFor(runtime)
        val token = state.lifecycle.request(mode)
        state.changes.value++
        mutableStatus.value = Status(mode, Phase.STARTING, "Preparing ${label(mode)}…")
        scope.launch {
            var result = "Failed: blocker setup did not complete"
            try {
                state.serial.withLock {
                    check(state.lifecycle.isCurrent(token, mode)) { "Superseded by a newer selection" }
                    check(mode == OFF || !state.processUnavailable) { "Extension process stopped; restart JA" }
                    val controller = runtime.webExtensionController
                    val ready = withTimeout(80_000L) {
                        val installed = awaitResult(controller.list()).orEmpty()
                        installed.filter { known(it.id) && (mode == OFF || it.id != idFor(mode)) }
                            .forEach { extension ->
                                if (extension.metaData.enabled) {
                                    awaitResult(controller.disable(extension, WebExtensionController.EnableSource.USER))
                                }
                                state.lifecycle.stopped(extension.id)
                                state.readyExtensions.remove(extension.id)
                            }
                        check(state.lifecycle.isCurrent(token, mode)) { "Selection changed" }
                        if (mode == OFF) {
                            val checked = awaitResult(controller.list()).orEmpty()
                            check(checked.none { known(it.id) && it.metaData.enabled }) { "A blocker is still enabled" }
                            null
                        } else {
                            val existing = installed.firstOrNull { it.id == idFor(mode) }
                            val enabled = if (existing == null) {
                                Log.i(TAG, "DOWNLOADING_SIGNED_ADDON mode=$mode source=Mozilla_AMO")
                                awaitResult(controller.install(if (mode == UBLOCK) UBLOCK_URL else ADGUARD_URL), true)
                            } else if (!existing.metaData.enabled) {
                                state.lifecycle.starting(existing.id)
                                state.readyExtensions.remove(existing.id)
                                awaitResult(controller.enable(existing, WebExtensionController.EnableSource.USER))
                            } else existing
                            checkNotNull(enabled) { "Gecko returned no extension" }
                            if (enabled.id != idFor(mode)) {
                                awaitResult(controller.disable(enabled, WebExtensionController.EnableSource.USER))
                                error("Unexpected extension ID; the downloaded add-on was disabled")
                            }
                            check(enabled.metaData.enabled) { "Extension is disabled" }
                            started(state, enabled, token, mode,
                                allowRestoredMetadata = existing?.metaData?.enabled == true)
                        }
                    }
                    check(state.lifecycle.isCurrent(token, mode)) { "Superseded by a newer selection" }
                    if (mode == OFF) {
                        mutableStatus.value = Status(mode, Phase.OFF, "Off: both filtering extensions are disabled. JA popup protection remains.")
                        result = "Off: native popup protection only"
                    } else {
                        val version = ready?.metaData?.version ?: "unknown"
                        mutableStatus.value = Status(mode, Phase.ENABLED,
                            "${label(mode)} $version enabled; Gecko startup completed. Filter health is not verified.", version)
                        result = "Active: ${label(mode)} $version (Gecko started; filter health unverified)"
                    }
                    Log.i(TAG, "BLOCKER_STATUS mode=$mode result=$result token=$token")
                }
            } catch (error: Exception) {
                result = "Failed: ${label(mode)} setup: ${error.message ?: error.javaClass.simpleName}"
                if (state.lifecycle.isCurrent(token, mode)) {
                    mutableStatus.value = Status(mode, Phase.FAILED, result)
                }
                Log.w(TAG, "BLOCKER_SETUP_FAILED mode=$mode token=$token", error)
            }
            try { done(result) } catch (error: Exception) { Log.w(TAG, "BLOCKER_CALLBACK_FAILED", error) }
        }
    }

    fun reportPlaybackProblem(mode: String, reason: String) {
        if (mode == OFF) return
        val old = mutableStatus.value
        if (old.mode == mode && old.phase in setOf(Phase.ENABLED, Phase.WARNING)) mutableStatus.value = old.copy(phase = Phase.WARNING,
            message = "Playback stalled with ${label(mode)} enabled. Cause unverified; inspect its logger or try Off.")
        Log.w(TAG, "BLOCKER_PLAYBACK_WARNING mode=$mode reason=$reason filterHealth=UNVERIFIED")
    }

    data class ManagementPages(val dashboard: String, val logger: String)
    fun managementPages(context: Context, done: (ManagementPages?, String?) -> Unit) {
        scope.launch {
            try {
                val runtime = GeckoRuntime.getDefault(context.applicationContext)
                val extension = withTimeout(10_000L) {
                    awaitResult(runtime.webExtensionController.list()).orEmpty().firstOrNull {
                        it.id == UBLOCK_ID && it.metaData.enabled
                    }
                } ?: error("Enable uBlock Origin first.")
                val base = extension.metaData.baseUrl.orEmpty()
                check(base.startsWith("moz-extension://")) { "uBlock pages are not ready yet. Try again shortly." }
                val dashboard = extension.metaData.optionsPageUrl ?: (base.trimEnd('/') + "/dashboard.html")
                done(ManagementPages(dashboard, base.trimEnd('/') + "/logger-ui.html"), null)
            } catch (error: Exception) { done(null, error.message ?: "Unable to open uBlock tools") }
        }
    }
}
