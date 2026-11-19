package com.example.ja;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** Calendar-day policy, deliberately independent of playback-provider availability. */
public final class JaEpisodeReleaseRules {
    private JaEpisodeReleaseRules() {}
    public static boolean visible(String airDate, LocalDate today, boolean enabled) {
        if (!enabled) return true;
        if (today == null || airDate == null || !airDate.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) return false;
        try { return LocalDate.parse(airDate).isBefore(today); }
        catch (DateTimeParseException invalid) { return false; }
    }
}
