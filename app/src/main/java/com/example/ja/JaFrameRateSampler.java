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
    private final java.util.ArrayDeque<double[]> window = new java.util.ArrayDeque<>();

    public double observe(int frames, double position) {
        if (frames <= 0 || Double.isNaN(position) || Double.isInfinite(position) || position < 0) {
            reset();
            return Double.NaN;
        }
        if (lastFrames < 0) {
            lastFrames = frames;
            lastPosition = position;
            window.add(new double[]{frames,position});
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
            window.clear();window.add(new double[]{frames,position});
            return Double.NaN;
        }
        double rate = frameDelta / seconds;
        if (rate < 20.0 || rate > 61.0) {
            clearStability();
            window.clear();window.add(new double[]{frames,position});
            return Double.NaN;
        }
        window.add(new double[]{frames,position});
        while(window.size()>5)window.removeFirst();
        if (!Double.isNaN(lastRate) && Math.abs(rate - lastRate) > 0.7) stableIntervals = 0;
        lastRate = rate;
        stableIntervals++;
        return stableIntervals >= 2 ? rate : averagedCadence();
    }
    private double averagedCadence() {
        if(window.size()<3)return Double.NaN;
        double[] first=window.peekFirst(),last=window.peekLast();
        double seconds=last[1]-first[1];
        if(seconds<1.75 || seconds>8.0)return Double.NaN;
        double mean=(last[0]-first[0])/seconds;
        double closest=Double.NaN,error=Double.POSITIVE_INFINITY;
        for(double candidate:new double[]{23.976,25.0,29.97,50.0,59.94}){
            double distance=Math.abs(mean-candidate);
            if(distance<error){error=distance;closest=candidate;}
        }
        if(error>0.30)return Double.NaN;
        // Bound disagreement with the aggregate. Large bursts and mixed-rate
        // intervals cannot earn confidence just because their average is familiar.
        for(double[] point:window)
            if(Math.abs((point[0]-first[0])-mean*(point[1]-first[1]))>2.1)return Double.NaN;
        return closest;
    }
    private void clearStability() {
        lastRate = Double.NaN;
        stableIntervals = 0;
    }
    public void reset() {
        lastFrames = -1;
        lastPosition = -1;
        window.clear();
        clearStability();
    }
}
