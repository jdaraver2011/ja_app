package com.example.ja;

/** Pure rules shared by watch tracking and paged-grid focus. */
public final class JaWatchRules {
    private JaWatchRules() {}
    public static boolean isWatched(long positionMs, long durationMs) {
        return durationMs > 0 && positionMs >= 0 && positionMs <= durationMs + 2000
            && (double) positionMs / durationMs >= 0.90;
    }
    public static int nextUnwatched(int[] episodes, boolean[] watched) {
        if (episodes.length != watched.length) throw new IllegalArgumentException("Mismatched episode flags");
        int next = Integer.MAX_VALUE;
        for (int i = 0; i < episodes.length; i++) {
            if (episodes[i] > 0 && !watched[i]) next = Math.min(next, episodes[i]);
        }
        return next == Integer.MAX_VALUE ? -1 : next;
    }
    public static int lastRowStart(int count, int columns) {
        if (count <= 0) return -1;
        int width = Math.max(1, columns);
        return ((count - 1) / width) * width;
    }
}
