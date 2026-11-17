package com.example.ja;

import java.util.Locale;

/** Shared compact resume labels. Total minutes keep the requested mm:ss layout. */
public final class JaResumeLabels {
    private JaResumeLabels() {}

    public static String time(long positionMs) {
        long seconds = Math.max(0L, positionMs) / 1000L;
        return String.format(Locale.US, "%02d:%02d", seconds / 60L, seconds % 60L);
    }

    public static String episodeLabel(int season, int episode, long positionMs) {
        String number = String.format(Locale.US, "S%02dE%02d", season, episode);
        return positionMs > 0L ? number + " (" + time(positionMs) + ")" : number;
    }

    public static String continueLabel(String type, int season, int episode, long positionMs) {
        return "tv".equals(type) ? episodeLabel(season, episode, positionMs) : time(positionMs);
    }
}
