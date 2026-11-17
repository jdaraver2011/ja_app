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
    public static boolean mayPlay(boolean videoActive, boolean focusGranted) {
        return !videoActive && focusGranted;
    }
}
