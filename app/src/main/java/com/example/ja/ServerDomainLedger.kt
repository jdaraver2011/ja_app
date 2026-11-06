package com.example.ja

import android.content.Context
import android.net.Uri
import android.util.Log

/**
 * Persistent, conservative provider-host migration ledger.
 *
 * A redirect is NOT automatically an address migration. Advertising redirects,
 * JavaScript navigation, and video CDN requests must never rewrite a source.
 * Only HTTPS, host-only substitutions for the original embed path are eligible;
 * a replacement is activated after media actually advances on that destination.
 * An existing provider host is reused instead of registering a duplicate.
 */
class ServerDomainLedger(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "ja_provider_domain_changes_v1", Context.MODE_PRIVATE
    )

    private fun host(url: String): String? = try {
        Uri.parse(url).host?.lowercase()?.trimEnd('.')?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }

    private fun choiceUrl(choice: PlaybackChoice): String = when (val s = choice.source) {
        is PlaybackSource.Native -> s.url
        is PlaybackSource.Embed -> s.url
    }

    private fun safeHost(h: String): Boolean = h.length in 4..253 &&
        h.contains('.') && h.all { it.isLetterOrDigit() || it == '-' || it == '.' } &&
        !h.matches(Regex("[0-9.]+")) &&
        !h.endsWith(".local") && !h.endsWith(".invalid") &&
        h !in setOf("localhost", "127.0.0.1")

    private fun swapHost(url: String, host: String): String? = try {
        val original = Uri.parse(url)
        if (original.scheme != "https" || !safeHost(host)) null
        else original.buildUpon().encodedAuthority(host).build().toString()
    } catch (_: Exception) { null }

    /**
     * Record a real HTTP redirect, rather than a third-party iframe/ad request.
     * Same-path preservation makes a new season/episode URL constructible.
     * Permanent 301/308 redirects can be probed immediately; temporary
     * redirects are only probe candidates after repeated independent sightings.
     */
    fun observeHttpRedirect(choice: PlaybackChoice, status: Int, location: String?, known: List<PlaybackChoice>) {
        if (status !in setOf(301, 302, 307, 308) || location.isNullOrBlank()) return
        if (choice.source !is PlaybackSource.Embed) return
        val old = Uri.parse(choiceUrl(choice))
        val target = try { Uri.parse(location) } catch (_: Exception) { return }
        val newHost = target.host?.lowercase()?.trimEnd('.') ?: return
        val oldHost = old.host?.lowercase()?.trimEnd('.') ?: return
        if (newHost == oldHost || target.scheme != "https" || !safeHost(newHost)) return
        val alreadyRegistered = known.any {
            it.id != choice.id && host(choiceUrl(it)) == newHost
        }
        // Unknown hosts must preserve the exact endpoint shape so JA can
        // generate subsequent seasons safely. For already-registered hosts,
        // their own provider URL builder supplies that shape.
        if (!alreadyRegistered && (target.encodedPath != old.encodedPath ||
                target.encodedQuery != old.encodedQuery)) {
            Log.i("JA_DOMAINS", "REDIRECT_NOT_HOST_ONLY id=${choice.id} from=$oldHost to=$newHost")
            return
        }
        // A provider's redirect must not silently take ownership of another
        // known provider's ID, or transform an ordinary analytics URL into video.
        val key = choice.id.substringBefore("~redirect")
        val last = prefs.getString("candidate_$key", null)
        val sightings = if (last == newHost) prefs.getInt("seen_$key", 0).coerceAtMost(1000) + 1 else 1
        val now = System.currentTimeMillis()
        val editor = prefs.edit()
        if (last != newHost) editor.putLong("first_seen_$key", now)
        editor
            .putInt("last_redirect_status_$key", status)
            .putString("candidate_$key", newHost)
            .putString("from_$key", oldHost)
            .putInt("seen_$key", sightings)
            .putBoolean("permanent_$key", status == 301 || status == 308 ||
                (last == newHost && prefs.getBoolean("permanent_$key", false)))
            .putLong("last_seen_$key", now)
            .apply()
        Log.i("JA_DOMAINS", "REDIRECT_FOUND id=$key status=$status from=$oldHost to=$newHost " +
            "seen=$sightings existing=$alreadyRegistered")
    }

    /** Called only for an actual blocked top-level GeckoView redirect. */
    fun observeNavigationRedirect(choice: PlaybackChoice, targetUrl: String, known: List<PlaybackChoice>) {
        // A request to navigate is weak evidence, not a permanent migration.
        // Store only with the same security and URL-shape checks as a 302.
        observeHttpRedirect(choice, 302, targetUrl, known)
    }

    /**
     * Dynamic source list: activated aliases replace the old host. Pending
     * aliases are additional candidates only; originals stay available until
     * a replacement actually emits VIDEO_STARTED.
     */
    fun apply(choices: List<PlaybackChoice>, includePending: Boolean = true): List<PlaybackChoice> {
        val knownHosts = choices.associateBy { host(choiceUrl(it)) }
        val out = mutableListOf<PlaybackChoice>()
        for (choice in choices) {
            if (choice.source !is PlaybackSource.Embed) { out.add(choice); continue }
            val key = choice.id.substringBefore("~redirect")
            val active = prefs.getString("active_$key", null)
            val pending = prefs.getString("candidate_$key", null)
            val target = active ?: pending
            val registered = target?.let { knownHosts[it] }?.takeIf { it.id != key }
            if (active != null) {
                if (registered != null) {
                    Log.i("JA_DOMAINS", "SOURCE_SUPPRESSED id=$key replacement=${registered.id}")
                    continue // Existing provider wins; no duplicate.
                }
                val replaced = swapHost(choiceUrl(choice), active)
                if (replaced != null) out.add(choice.copy(source = PlaybackSource.Embed(replaced, active)))
                else out.add(choice)
                continue
            }
            out.add(choice)
            val enoughEvidence = prefs.getBoolean("permanent_$key", false) ||
                prefs.getInt("seen_$key", 0) >= 2
            if (includePending && pending != null && registered == null && enoughEvidence) {
                val rewritten = swapHost(choiceUrl(choice), pending)
                if (rewritten != null) out.add(PlaybackChoice("$key~redirect", "${choice.label} (moved)",
                    PlaybackSource.Embed(rewritten, pending)))
            }
        }
        // Two providers may legitimately use the same host but different
        // embed paths. Only exact URL matches are duplicates.
        return out.distinctBy(::choiceUrl)
    }

    /** Promote a proposed replacement only after its video signal is observed. */
    fun onMediaStarted(choice: PlaybackChoice, choices: List<PlaybackChoice>): String {
        val id = choice.id.substringBefore("~redirect")
        val seen = host(choiceUrl(choice)) ?: return id
        // When the redirected host already has a registered provider, video
        // can only be observed through that existing provider (no duplicate
        // alias is created). Retire the stale provider after its replacement
        // has independently started media.
        for (other in choices) {
            if (other.id == id || other.source !is PlaybackSource.Embed) continue
            val oldId = other.id.substringBefore("~redirect")
            if (prefs.getString("candidate_$oldId", null) != seen) continue
            if (!(prefs.getBoolean("permanent_$oldId", false) ||
                    prefs.getInt("seen_$oldId", 0) >= 2)) continue
            prefs.edit().putString("active_$oldId", seen)
                .putLong("activated_at_$oldId", System.currentTimeMillis()).apply()
            Log.i("JA_DOMAINS", "DOMAIN_PROMOTED id=$oldId host=$seen existing=$id")
        }
        val pending = prefs.getString("candidate_$id", null)
        if (pending == seen && (prefs.getBoolean("permanent_$id", false) ||
                    prefs.getInt("seen_$id", 0) >= 2)) {
            prefs.edit().putString("active_$id", seen)
                .putLong("activated_at_$id", System.currentTimeMillis()).apply()
            val existing = choices.firstOrNull { it.id != id && host(choiceUrl(it)) == seen }
            Log.i("JA_DOMAINS", "DOMAIN_PROMOTED id=$id host=$seen " +
                "existing=${existing?.id ?: "none"}")
            return existing?.id ?: id
        }
        return id
    }
}
