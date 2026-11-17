package com.example.ja;

import java.net.URI;
import java.util.Locale;

/** Navigation is limited to Deezer and explicit sign-in providers; media subresources are untouched. */
public final class JaMusicRules {
    private JaMusicRules() {}
    public static boolean allowed(String value) {
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                    || uri.getRawUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) return false;
            host = host.toLowerCase(Locale.ROOT);
            return host.equals("deezer.com") || host.endsWith(".deezer.com")
                || host.equals("accounts.google.com") || host.equals("appleid.apple.com")
                || host.equals("facebook.com") || host.equals("www.facebook.com")
                || host.equals("m.facebook.com");
        } catch (Exception ignored) { return false; }
    }
    public static boolean mayPlay(boolean videoActive, boolean focusGranted) {
        return !videoActive && focusGranted;
    }
}
