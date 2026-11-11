package com.example.ja;

/** Estimates media cadence from cumulative decoded frames and media time.
 * Accepts the bridge's one-second reports and older five-second reports.
 * This is a cadence estimate, not authoritative encoded frame-rate metadata.
 */
public final class JaFrameRateSampler {
    private int lastFrames = -1;
    private double lastPosition = -1;
    private double lastRate = Double.NaN;
    private int stableIntervals;

    public double observe(int frames, double position) {
        if (frames <= 0 || Double.isNaN(position) || Double.isInfinite(position) || position < 0) {
            reset();
            return Double.NaN;
        }
        if (lastFrames < 0) {
            lastFrames = frames;
            lastPosition = position;
            return Double.NaN;
        }
        int frameDelta = frames - lastFrames;
        double seconds = position - lastPosition;
        // Tiny/duplicate reports retain the baseline so valid short reports can
        // accumulate. Seek/reset/pause discontinuities start a fresh baseline.
        if (seconds >= 0 && seconds < 0.5 && frameDelta >= 0) return Double.NaN;
        lastFrames = frames;
        lastPosition = position;
        if (seconds < 0.5 || seconds > 8.0 || frameDelta <= 0) {
            clearStability();
            return Double.NaN;
        }
        double rate = frameDelta / seconds;
        if (rate < 20.0 || rate > 61.0) {
            clearStability();
            return Double.NaN;
        }
        if (!Double.isNaN(lastRate) && Math.abs(rate - lastRate) > 0.7) stableIntervals = 0;
        lastRate = rate;
        stableIntervals++;
        return stableIntervals >= 2 ? rate : Double.NaN;
    }
    private void clearStability() {
        lastRate = Double.NaN;
        stableIntervals = 0;
    }
    public void reset() {
        lastFrames = -1;
        lastPosition = -1;
        clearStability();
    }
}
