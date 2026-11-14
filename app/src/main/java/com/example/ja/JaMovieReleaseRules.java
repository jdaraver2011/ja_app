package com.example.ja;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** Calendar classification uses TMDB's primary date, not regional release-type coverage. */
public final class JaMovieReleaseRules {
    public enum Bucket { RELEASED, UPCOMING, TODAY, UNKNOWN }
    private JaMovieReleaseRules() {}
    public static Bucket classify(String primaryDate, LocalDate today) {
        if (primaryDate == null || !primaryDate.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) return Bucket.UNKNOWN;
        try {
            LocalDate date = LocalDate.parse(primaryDate);
            if (date.isBefore(today)) return Bucket.RELEASED;
            if (date.isAfter(today)) return Bucket.UPCOMING;
            return Bucket.TODAY;
        } catch (DateTimeParseException error) { return Bucket.UNKNOWN; }
    }
    public static String releasedThrough(LocalDate today) { return today.minusDays(1).toString(); }
    public static String upcomingFrom(LocalDate today) { return today.plusDays(1).toString(); }
    /** Other wins if independent cached responses disagree; movie IDs never hide TV IDs. */
    public static boolean includeOutsideOther(String mediaType, int id, java.util.Set<Integer> otherIds) {
        return !"movie".equals(mediaType) || !otherIds.contains(id);
    }
}
