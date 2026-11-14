package com.example.ja;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** Release metadata is a browsing rule, never proof of provider playback or quality. */
public final class JaMovieReleaseRules {
    public static final String REGION = "US";
    private JaMovieReleaseRules() {}
    /** Other wins if cached API responses disagree; movie IDs never hide TV IDs. */
    public static boolean includeOutsideOther(String mediaType, int id, java.util.Set<Integer> otherIds) {
        return !"movie".equals(mediaType) || !otherIds.contains(id);
    }
    public static boolean qualifies(int type, String date, LocalDate today) {
        if (type != 4 && type != 6) return false;
        if (date == null || date.length() < 10) return false;
        try {
            LocalDate released = LocalDate.parse(date.substring(0, 10));
            return !released.isAfter(today.minusDays(1));
        } catch (DateTimeParseException error) { return false; }
    }
}
