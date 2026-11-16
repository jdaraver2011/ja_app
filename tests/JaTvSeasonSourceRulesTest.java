package com.example.ja;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class JaTvSeasonSourceRulesTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        List<String> defaults = Arrays.asList("vidsrc", "vidfast-vc", "cinejoy");
        String episode1 = JaTvSeasonSourceRules.key("tv", 1399, 1, 1);
        String episode8 = JaTvSeasonSourceRules.key("tv", 1399, 1, 8);
        check(episode1.equals(episode8), "Episodes in the same season must share the preference");
        check(!episode1.equals(JaTvSeasonSourceRules.key("tv", 1399, 2, 1)), "Seasons must be isolated");
        check(!episode1.equals(JaTvSeasonSourceRules.key("tv", 1400, 1, 1)), "Series must be isolated");
        check(JaTvSeasonSourceRules.key("tv", 1399, 0, 1) != null, "Support Specials");
        check(JaTvSeasonSourceRules.key("movie", 1399, 1, 1) == null, "Movies cannot learn TV preferences");
        check(JaTvSeasonSourceRules.key("tv", 0, 1, 1) == null, "Reject invalid title");
        check(JaTvSeasonSourceRules.key("tv", 1399, -1, 1) == null, "Reject invalid season");
        check(JaTvSeasonSourceRules.key("tv", 1399, 1, 0) == null, "Require a real episode");
        check(JaTvSeasonSourceRules.order(defaults, "vidfast-vc").equals(
            Arrays.asList("vidfast-vc", "vidsrc", "cinejoy")), "Promote VidFast, preserve fallback");
        check(JaTvSeasonSourceRules.order(defaults, "cinejoy").equals(
            Arrays.asList("cinejoy", "vidsrc", "vidfast-vc")), "Promote CineJoy, preserve fallback");
        check(JaTvSeasonSourceRules.order(defaults, "vidsrc").equals(defaults), "Already preferred");
        check(JaTvSeasonSourceRules.order(defaults, "disabled-server").equals(defaults), "No disabled provider injection");
        check(JaTvSeasonSourceRules.order(defaults, null).equals(defaults), "Unlearned/reset season uses default order");
        check(defaults.equals(Arrays.asList("vidsrc", "vidfast-vc", "cinejoy")), "Do not mutate allowlist");
        check(JaTvSeasonSourceRules.order(Arrays.asList(), "vidfast-vc").isEmpty(), "Handle no sources");
        Map<String, String> learned = new HashMap<>();
        learned.put(episode1, "vidfast-vc");
        check(JaTvSeasonSourceRules.order(defaults, learned.get(episode8)).get(0).equals("vidfast-vc"),
            "A later episode must reuse the working server");
        check(JaTvSeasonSourceRules.order(defaults, learned.get(
            JaTvSeasonSourceRules.key("tv", 1399, 2, 1))).equals(defaults), "No unplayed-season bias");
        learned.clear();
        check(JaTvSeasonSourceRules.order(defaults, learned.get(episode8)).equals(defaults), "Cleared preference restores defaults");
        System.out.println("PASS: TV-only season isolation, Specials, enabled-source promotion and fallback/reset order");
    }
}
