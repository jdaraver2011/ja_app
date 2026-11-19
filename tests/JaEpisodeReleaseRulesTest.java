package com.example.ja;

import java.time.LocalDate;
public final class JaEpisodeReleaseRulesTest {
    private static void check(boolean result, String label) {
        if (!result) throw new AssertionError(label);
    }
    public static void main(String[] args) {
        LocalDate today = LocalDate.of(2026, 11, 19);
        check(JaEpisodeReleaseRules.visible("2026-11-18", today, true), "Yesterday released");
        check(!JaEpisodeReleaseRules.visible("2026-11-19", today, true), "Today hidden");
        check(!JaEpisodeReleaseRules.visible("2026-11-20", today, true), "Future hidden");
        check(JaEpisodeReleaseRules.visible("2026-11-19", today.plusDays(1), true), "Appears on following day");
        check(JaEpisodeReleaseRules.visible("2024-02-29", LocalDate.of(2024,3,1), true), "Leap-day boundary");
        check(JaEpisodeReleaseRules.visible("2026-12-31", LocalDate.of(2027,1,1), true), "Year boundary");
        for (String invalid : new String[]{null, "", "null", "2026-02-30", "2026-2-01", "2026-11-19T00:00:00"}) {
            check(!JaEpisodeReleaseRules.visible(invalid,today,true), "Hide missing/invalid date");
            check(JaEpisodeReleaseRules.visible(invalid,today,false), "Disabled restores unknown dates");
        }
        check(JaEpisodeReleaseRules.visible("2099-01-01",today,false), "Disabled restores future dates");
        check(!JaEpisodeReleaseRules.visible("2020-01-01",null,true), "Missing comparison date");
        System.out.println("PASS: one-day episode delay, setting bypass, invalid dates and calendar boundaries");
    }
}
