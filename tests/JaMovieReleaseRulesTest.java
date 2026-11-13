package com.example.ja;
import java.time.LocalDate;
public final class JaMovieReleaseRulesTest {
    private static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) {
        LocalDate today = LocalDate.of(2026, 11, 13);
        check(JaMovieReleaseRules.qualifies(4, "2026-11-12T23:59:59.000Z", today));
        check(JaMovieReleaseRules.qualifies(6, "2026-11-12", today));
        check(!JaMovieReleaseRules.qualifies(4, "2026-11-13", today));
        check(!JaMovieReleaseRules.qualifies(6, "2026-11-14", today));
        for (int type : new int[]{0, 1, 2, 3, 5}) {
            check(!JaMovieReleaseRules.qualifies(type, "2000-01-01", today));
        }
        for (String invalid : new String[]{null, "", "null", "nonsense!", "2026-02-30"}) {
            check(!JaMovieReleaseRules.qualifies(4, invalid, today));
        }
        check(!JaMovieReleaseRules.qualifies(4, "2026-11-13", today));
        check(JaMovieReleaseRules.qualifies(4, "2026-11-13", today.plusDays(1)));
        check(JaMovieReleaseRules.qualifies(4, "2024-02-29", LocalDate.of(2024,3,1)));
        check(JaMovieReleaseRules.qualifies(4, "2025-12-31", LocalDate.of(2026,1,1)));
        java.util.Set<Integer> other = java.util.Set.of(42, 99);
        check(!JaMovieReleaseRules.includeOutsideOther("movie", 42, other));
        check(JaMovieReleaseRules.includeOutsideOther("tv", 42, other));
        check(JaMovieReleaseRules.includeOutsideOther("movie", 7, other));
        check(JaMovieReleaseRules.includeOutsideOther("movie", 42, java.util.Set.of()));
        System.out.println("PASS: digital/TV cutoff, today/future/theatrical/physical exclusion, missing dates, midnight/leap/year boundaries");
    }
}
