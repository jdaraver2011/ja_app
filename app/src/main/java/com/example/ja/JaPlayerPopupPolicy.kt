package com.example.ja

import java.util.Locale

/**
 * Ad/scam JavaScript dialogs known to interrupt embedded playback.
 * This deliberately does not block all JavaScript prompts: unrelated sites
 * may legitimately require a player action or an authorization decision.
 */
internal object JaPlayerPopupPolicy {
    fun shouldDismiss(title: String?, message: String?): Boolean {
        // Gecko may report either a bare hostname or a full origin as the
        // prompt title. Match exact host or subdomain, not arbitrary strings.
        val rawTitle = title.orEmpty().trim().lowercase(Locale.ROOT)
        val host = rawTitle.substringAfter("://", rawTitle)
            .substringBefore('/').substringBefore(' ').substringBefore(':')
            .trimEnd('.')
        if (host == "vidspark.to" || host.endsWith(".vidspark.to")) return true

        // Some Gecko variants omit the origin on prompts from child frames.
        // Restrict fallback matching to the specific deceptive messages seen
        // in diagnostics; normal controls/menu prompts remain untouched.
        val body = message.orEmpty().trim().lowercase(Locale.ROOT)
        return body.startsWith("streaming now:") ||
            body.contains("vpn must be activated") ||
            body.contains("install clearvpn")
    }
}
