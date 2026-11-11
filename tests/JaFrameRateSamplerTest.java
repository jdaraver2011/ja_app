package com.example.ja;

public final class JaFrameRateSamplerTest {
    private static void absent(double value) {
        if (!Double.isNaN(value)) throw new AssertionError("Accepted invalid/unstable cadence: " + value);
    }
    private static void near(double value, double expected) {
        if (Double.isNaN(value) || Math.abs(value - expected) > 0.35)
            throw new AssertionError("Expected " + expected + ", got " + value);
    }
    public static void main(String[] args) {
        JaFrameRateSampler s = new JaFrameRateSampler();
        // Real timestamps from the user's review1 log. The old 3.5-second
        // minimum rejected every one of these normal one-second reports.
        absent(s.observe(97, 4.154));
        absent(s.observe(121, 5.149));
        near(s.observe(145, 6.145), 24.0);
        near(s.observe(169, 7.139), 24.0);
        // Previous five-second bridge cadence remains compatible.
        s.reset();absent(s.observe(120, 5));absent(s.observe(240, 10));near(s.observe(360, 15),24);
        // Seek jump, counter reset and changed cadence require fresh stability.
        absent(s.observe(480, 100));absent(s.observe(504,101));near(s.observe(528,102),24);
        absent(s.observe(1,0));absent(s.observe(25,1));near(s.observe(49,2),24);
        absent(s.observe(79,3));near(s.observe(109,4),30);
        // Duplicate reports and brief report intervals must not destroy a baseline.
        s.reset();absent(s.observe(120,5));absent(s.observe(120,5));absent(s.observe(126,5.25));
        absent(s.observe(144,6));near(s.observe(168,7),24);
        // Invalid data cannot retain an old stable estimate.
        absent(s.observe(-1,8));absent(s.observe(192,8));absent(s.observe(216,9));near(s.observe(240,10),24);
        absent(s.observe(264,Double.NaN));absent(s.observe(288,Double.POSITIVE_INFINITY));
        // A stalled timestamp cannot produce a refresh request.
        s.reset();absent(s.observe(120,5));absent(s.observe(120,5));absent(s.observe(120,5));
        System.out.println("PASS: real one-second trace, five-second cadence, seeks, resets, duplicates, stalls and cadence changes");
    }
}
