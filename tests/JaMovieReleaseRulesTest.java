package com.example.ja;
import java.time.LocalDate;
import java.util.Set;
import static com.example.ja.JaMovieReleaseRules.Bucket.*;
public final class JaMovieReleaseRulesTest {
    private static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) {
        LocalDate today = LocalDate.of(2026,11,13);
        // Latest possible dates within the example years, not claimed exact TMDB dates.
        int[] years = {2017,2008,2010,2009,1972,2001,2004,2001,2019};
        for (int year : years) check(JaMovieReleaseRules.classify(LocalDate.of(year,12,31).toString(), today)==RELEASED);
        check(JaMovieReleaseRules.classify("2026-11-12",today)==RELEASED);
        check(JaMovieReleaseRules.classify("2026-11-13",today)==TODAY);
        check(JaMovieReleaseRules.classify("2026-11-14",today)==UPCOMING);
        for (String bad : new String[]{null,"","null","2017","2026-02-30","2026-11-14T00:00:00Z"," 2026-11-14"}) {
            check(JaMovieReleaseRules.classify(bad,today)==UNKNOWN);
        }
        // Future -> release day hold -> normal lists on the next day.
        check(JaMovieReleaseRules.classify("2026-11-14",today.plusDays(1))==TODAY);
        check(JaMovieReleaseRules.classify("2026-11-14",today.plusDays(2))==RELEASED);
        check(JaMovieReleaseRules.classify("2024-02-29",LocalDate.of(2024,3,1))==RELEASED);
        check(JaMovieReleaseRules.releasedThrough(today).equals("2026-11-12"));
        check(JaMovieReleaseRules.upcomingFrom(today).equals("2026-11-14"));
        check(JaMovieReleaseRules.releasedThrough(LocalDate.of(2026,1,1)).equals("2025-12-31"));
        check(JaMovieReleaseRules.upcomingFrom(LocalDate.of(2026,12,31)).equals("2027-01-01"));
        // No valid date can enter both released and upcoming across a calendar boundary.
        for(int d=-370;d<370;d++) {
            LocalDate date=today.plusDays(d);
            var bucket=JaMovieReleaseRules.classify(date.toString(),today);
            check((bucket==RELEASED)==!date.isAfter(LocalDate.parse(JaMovieReleaseRules.releasedThrough(today))));
            check((bucket==UPCOMING)==!date.isBefore(LocalDate.parse(JaMovieReleaseRules.upcomingFrom(today))));
        }
        check(!JaMovieReleaseRules.includeOutsideOther("movie",42,Set.of(42)));
        check(JaMovieReleaseRules.includeOutsideOther("tv",42,Set.of(42)));
        System.out.println("PASS: nine example years, primary-date boundaries, automatic promotion, missing/invalid dates and disjoint movie buckets");
    }
}
