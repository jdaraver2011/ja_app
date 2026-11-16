package com.example.ja;

import java.util.ArrayList;
import java.util.List;

/** Pure policy for TV-only season keys and stable promotion of enabled sources. */
public final class JaTvSeasonSourceRules {
    private JaTvSeasonSourceRules() {}

    public static String key(String type, int tmdbId, int season, int episode) {
        if (!"tv".equals(type) || tmdbId <= 0 || season < 0 || episode <= 0) return null;
        // Season zero is TMDB's Specials season and is isolated like any other.
        return "tv_season_v212:" + tmdbId + ":" + season;
    }

    public static List<String> order(List<String> enabledSources, String preferred) {
        List<String> ordered = new ArrayList<>(enabledSources);
        int index = preferred == null ? -1 : ordered.indexOf(preferred);
        if (index > 0) ordered.add(0, ordered.remove(index));
        // Missing/disabled preferences never add a source or a network probe.
        // Remaining providers retain the current allowlist order for fallback.
        return ordered;
    }
}
