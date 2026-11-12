package com.example.ja;
public final class JaWatchRulesTest {
    private static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) {
        check(!JaWatchRules.isWatched(89999, 100000));
        check(JaWatchRules.isWatched(90000, 100000));
        check(JaWatchRules.isWatched(100000, 100000));
        check(!JaWatchRules.isWatched(90000, 0));
        check(!JaWatchRules.isWatched(90000, -1));
        check(!JaWatchRules.isWatched(-1, 100000));
        check(!JaWatchRules.isWatched(150000, 100000));
        check(JaWatchRules.lastRowStart(20, 7) == 14);
        check(JaWatchRules.lastRowStart(20, 6) == 18);
        check(JaWatchRules.lastRowStart(20, 5) == 15);
        check(JaWatchRules.lastRowStart(1, 5) == 0);
        check(JaWatchRules.lastRowStart(0, 5) == -1);
        check(JaWatchRules.nextUnwatched(new int[]{3,1,2}, new boolean[]{false,true,true}) == 3);
        check(JaWatchRules.nextUnwatched(new int[]{1,2,3}, new boolean[]{false,true,false}) == 1);
        check(JaWatchRules.nextUnwatched(new int[]{1,2}, new boolean[]{true,true}) == -1);
        check(JaWatchRules.nextUnwatched(new int[]{}, new boolean[]{}) == -1);
        System.out.println("PASS: 90-percent threshold, invalid/live durations, bad positions and incomplete rows");
    }
}
