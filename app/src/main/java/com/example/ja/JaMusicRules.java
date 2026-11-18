package com.example.ja;

import java.net.URI;

/** Music-only HTTPS navigation policy. Movie/TV source restrictions are independent. */
public final class JaMusicRules {
    private JaMusicRules() {}
    public static boolean allowed(String value) {
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                    || uri.getRawUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) return false;
            return true;
        } catch (Exception ignored) { return false; }
    }
    /** Upgrade only Deezer's legacy HTTP destinations, preserving encoded login parameters. */
    public static String secureDeezerRedirect(String value) {
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            if (!"http".equalsIgnoreCase(uri.getScheme()) || host == null
                    || uri.getRawUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 80))
                return null;
            host = host.toLowerCase(java.util.Locale.ROOT);
            if (!host.equals("deezer.com") && !host.endsWith(".deezer.com")) return null;
            // Use raw URI components so query/fragment-only URLs are also preserved exactly.
            String upgraded = "https://" + host + (uri.getRawPath() == null ? "" : uri.getRawPath())
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())
                + (uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment());
            return allowed(upgraded) ? upgraded : null;
        } catch (Exception ignored) { return null; }
    }
    /** A page may upgrade at most three redirects per 30 seconds, including across page starts. */
    public static final class RedirectGuard {
        private long started = -1;
        private int attempts;
        public boolean tryUpgrade(long now) {
            if (started < 0 || now < started || now - started >= 30_000) {
                started = now;
                attempts = 0;
            }
            return ++attempts <= 3;
        }
        public void reset() { started = -1; attempts = 0; }
    }
    public static boolean mayPlay(boolean videoActive, boolean focusGranted) {
        return !videoActive && focusGranted;
    }
}
