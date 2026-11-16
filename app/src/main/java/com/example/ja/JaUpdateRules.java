package com.example.ja;

import java.util.Set;

/** Acceptance rules for a downloaded in-place update, independent of Android UI. */
public final class JaUpdateRules {
    private JaUpdateRules() {}
    public static boolean versionMatches(String expectedPackage, String actualPackage,
            long installed, long metadata, long archive) {
        return expectedPackage != null && expectedPackage.equals(actualPackage)
                && metadata > installed && archive == metadata;
    }
    public static boolean signersMatch(Set<String> installed, Set<String> archive) {
        return installed != null && !installed.isEmpty() && installed.equals(archive);
    }
    public static boolean downloadMatches(String expected, String actual, long declared, long received) {
        return expected != null && expected.matches("[0-9a-f]{64}") && expected.equals(actual)
                && declared > 0 && declared == received;
    }
}
