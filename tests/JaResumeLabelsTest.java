package com.example.ja;

public final class JaResumeLabelsTest {
    private static void expect(String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError(expected + " != " + actual);
    }

    public static void main(String[] args) {
        expect("00:00", JaResumeLabels.time(0));
        expect("00:00", JaResumeLabels.time(-1));
        expect("01:05", JaResumeLabels.time(65_999));
        expect("125:04", JaResumeLabels.time(7_504_000));
        expect("12:34", JaResumeLabels.continueLabel("movie", 0, 0, 754_000));
        expect("S17E03 (12:34)", JaResumeLabels.continueLabel("tv", 17, 3, 754_000));
        expect("S00E01 (00:01)", JaResumeLabels.episodeLabel(0, 1, 1000));
        expect("S17E03", JaResumeLabels.episodeLabel(17, 3, 0));
        expect("S17E03", JaResumeLabels.episodeLabel(17, 3, -1));
        expect("S100E123 (01:00)", JaResumeLabels.episodeLabel(100, 123, 60_000));
        System.out.println("Resume label tests passed");
    }
}
