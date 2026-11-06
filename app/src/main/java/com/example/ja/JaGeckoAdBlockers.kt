package com.example.ja

import android.util.Log
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import java.lang.reflect.Proxy
import java.lang.reflect.InvocationHandler
import java.util.WeakHashMap

/**
 * Install the actual Mozilla-signed Firefox extensions in JA's GeckoRuntime.
 * These are downloaded from AMO on first use, NOT copied out of the user's
 * Firefox app and NOT installed as unsigned/injected JavaScript.
 *
 * Running two independent filtering engines together causes duplicated network
 * interception and frequently breaks video requests. Choose exactly one.
 */
internal object JaGeckoAdBlockers {
    const val OFF = "off"
    const val UBLOCK = "ublock"
    const val ADGUARD = "adguard"

    private const val TAG = "JA_ADBLOCK"
    private const val UBLOCK_ID = "uBlock0@raymondhill.net"
    private const val ADGUARD_ID = "adguardadblocker@adguard.com"
    private const val UBLOCK_URL =
        "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi"
    private const val ADGUARD_URL =
        "https://addons.mozilla.org/firefox/downloads/latest/adguard-adblocker/latest.xpi"

    fun normalized(mode: String): String = when (mode) {
        OFF, UBLOCK, ADGUARD -> mode
        else -> UBLOCK
    }

    fun label(mode: String): String = when (normalized(mode)) {
        OFF -> "Off"
        ADGUARD -> "AdGuard"
        else -> "uBlock Origin"
    }

    private class RuntimeState {
        var busy = false
        val waiting = mutableListOf<Pair<String, (String) -> Unit>>()
        var mode: String? = null
    }

    private val states = WeakHashMap<GeckoRuntime, RuntimeState>()

    /**
     * Callback is invoked once after the extension has been enabled/disabled,
     * or on an error (the existing JA popup guard remains as fallback).
     * Caller must also enforce a finite wait, to handle offline AMO requests.
     */
    fun prepare(runtime: GeckoRuntime, requestedMode: String, done: (String) -> Unit) {
        val mode = normalized(requestedMode)
        val state = synchronized(states) { states.getOrPut(runtime) { RuntimeState() } }
        synchronized(state) {
            if (state.busy) {
                state.waiting.add(mode to done)
                return
            }
            state.busy = true
            state.waiting.add(mode to done)
        }

        val controller = runtime.webExtensionController
        // The JA project currently compiles against an older GeckoView API.
        // On that API installation prompts use onInstallPrompt() returning
        // GeckoResult<AllowOrDeny>. Newer GeckoView versions use
        // onInstallPromptRequest() with PermissionPromptResponse (introduced
        // in v133). A Java interface proxy avoids a compile-time reference to
        // methods/classes absent from the installed GeckoView dependency.
        // Only the two explicitly selected Mozilla add-on IDs are approved.
        val promptInterface = WebExtensionController.PromptDelegate::class.java
        controller.promptDelegate = Proxy.newProxyInstance(
            promptInterface.classLoader,
            arrayOf(promptInterface),
            InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "equals" -> proxy === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "JA trusted add-on permission prompt"
                    "onInstallPrompt", "onOptionalPrompt", "onUpdatePrompt" -> {
                        val ext = args?.firstOrNull() as? WebExtension
                        val allowed = ext?.id == UBLOCK_ID || ext?.id == ADGUARD_ID
                        // Only installation and optional permissions for these
                        // two vetted IDs get approved; never silently approve
                        // extension updates requiring NEW permissions.
                        val approve = allowed && method.name != "onUpdatePrompt"
                        Log.i(TAG, "ADDON_PERMISSION kind=${method.name} id=${ext?.id} allow=$approve")
                        GeckoResult.fromValue(if (approve) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
                    }
                    "onInstallPromptRequest" -> {
                        val ext = args?.firstOrNull() as? WebExtension
                        val approve = ext?.id == UBLOCK_ID || ext?.id == ADGUARD_ID
                        Log.i(TAG, "ADDON_INSTALL_PERMISSION id=${ext?.id} allow=$approve")
                        // GeckoView v133+ moved to a new response class.
                        // Reflectively construct the 2-boolean response, if
                        // available, while keeping the older build compiling.
                        val response = try {
                            val cls = Class.forName("org.mozilla.geckoview.WebExtension\$PermissionPromptResponse")
                            val ctor = cls.constructors.firstOrNull { it.parameterTypes.size == 2 &&
                                it.parameterTypes.all { type -> type == Boolean::class.javaPrimitiveType } }
                            ctor?.newInstance(approve, false)
                        } catch (e: ReflectiveOperationException) {
                            Log.w(TAG, "ADDON_PERMISSION_RESPONSE_UNAVAILABLE", e)
                            null
                        }
                        if (response != null) GeckoResult.fromValue(response) else null
                    }
                    else -> null
                }
            }
        ) as WebExtensionController.PromptDelegate

        fun finish(status: String) {
            val (callbacks, queued) = synchronized(state) {
                state.busy = false
                if (status.startsWith("Active:") || mode == OFF) state.mode = mode
                val matches = state.waiting.filter { it.first == mode }.map { it.second }
                val others = state.waiting.filter { it.first != mode }
                state.waiting.clear()
                Pair(matches, others)
            }
            Log.i(TAG, "BLOCKER_STATUS mode=$mode result=$status")
            callbacks.forEach { callback -> callback(status) }
            // A settings change during a slow install is queued and reconciled
            // after this install completes, not falsely marked as active.
            queued.forEach { (newMode, callback) -> prepare(runtime, newMode, callback) }
        }

        fun activate(installed: List<WebExtension>) {
            if (mode == OFF) {
                finish("Off: native popup blocking only")
                return
            }
            val id = if (mode == UBLOCK) UBLOCK_ID else ADGUARD_ID
            val existing = installed.firstOrNull { it.id == id }
            if (existing != null) {
                if (existing.metaData.enabled) {
                    finish("Active: ${label(mode)}")
                } else {
                    controller.enable(existing, WebExtensionController.EnableSource.USER).accept(
                        { finish("Active: ${label(mode)}") },
                        { error -> finish("Failed: ${label(mode)} enable: ${error?.message ?: "unknown error"}") }
                    )
                }
            } else {
                Log.i(TAG, "DOWNLOADING_SIGNED_ADDON name=${label(mode)} source=Mozilla_AMO")
                val url = if (mode == UBLOCK) UBLOCK_URL else ADGUARD_URL
                controller.install(url).accept(
                    { extension ->
                        if (extension == null) {
                            finish("Failed: ${label(mode)} installation returned no extension")
                        } else if (extension.id != id) {
                            // Defense in depth: a redirected install should not
                            // silently run an extension with an unexpected ID.
                            controller.disable(extension, WebExtensionController.EnableSource.USER)
                            finish("Failed: unexpected add-on ID ${extension.id}")
                        } else if (extension.metaData.enabled) {
                            finish("Active: ${label(mode)}")
                        } else {
                            finish("Failed: ${label(mode)} was installed but is disabled")
                        }
                    },
                    { error -> finish("Failed: ${label(mode)} install: ${error?.message ?: "unknown error"}") }
                )
            }
        }

        controller.list().accept(
            { result ->
                val installed: List<WebExtension> = result ?: emptyList()
                // Disable the unselected engine BEFORE enabling the selected
                // one. Never run uBlock Origin and AdGuard at once.
                val unwanted = installed.filter { ext ->
                    (ext.id == UBLOCK_ID && mode != UBLOCK) ||
                        (ext.id == ADGUARD_ID && mode != ADGUARD)
                }
                fun disableNext(index: Int) {
                    if (index >= unwanted.size) {
                        activate(installed)
                        return
                    }
                    val ext = unwanted[index]
                    if (!ext.metaData.enabled) {
                        disableNext(index + 1)
                        return
                    }
                    controller.disable(ext, WebExtensionController.EnableSource.USER).accept(
                        { disableNext(index + 1) },
                        { error ->
                            Log.w(TAG, "DISABLE_FAILED id=${ext.id}", error)
                            // Avoid enabling the other blocker if we failed to
                            // disable the previous one, preventing conflicts.
                            finish("Failed: could not disable ${ext.id}")
                        }
                    )
                }
                disableNext(0)
            },
            { error -> finish("Failed: extension list: ${error?.message ?: "unknown error"}") }
        )
    }
}
