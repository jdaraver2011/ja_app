package com.example.ja;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Bounded, allocation-free stereo audio-thread capture. Never mutates the decoder's buffer. */
public final class JaMusicPcm {
    private final int[] samples = new int[1024];
    private final int[] leftSamples = new int[1024];
    private final int[] rightSamples = new int[1024];
    private int cursor, channels = 2;
    private volatile boolean enabled;
    private long updated;

    public JaMusicPcm() {
        Arrays.fill(samples, 128);
        Arrays.fill(leftSamples, 128);
        Arrays.fill(rightSamples, 128);
    }

    public synchronized void enable(boolean value) {
        enabled = value; cursor = 0; updated = 0;
        Arrays.fill(samples, 128); Arrays.fill(leftSamples, 128); Arrays.fill(rightSamples, 128);
    }

    public synchronized void format(int channelCount) {
        channels = Math.max(1, channelCount); cursor = 0; updated = 0;
        Arrays.fill(samples, 128); Arrays.fill(leftSamples, 128); Arrays.fill(rightSamples, 128);
    }

    public void capture16(ByteBuffer buffer, long now) {
        if (!enabled) return;
        synchronized (this) {
            int step = channels * 2;
            for (int p = buffer.position(); p + step <= buffer.limit(); p += step) {
                int total = 0;
                int left = 0;
                int right = 0;
                for (int c = 0; c < channels; c++) {
                    int at = p + c * 2;
                    int value = (short) ((buffer.get(at) & 255) | (buffer.get(at + 1) << 8));
                    total += value;
                    if (c == 0) left = value;
                    if (c == 1) right = value;
                }
                if (channels == 1) right = left;
                samples[cursor] = toByte(total / channels);
                leftSamples[cursor] = toByte(left);
                rightSamples[cursor] = toByte(right);
                cursor = (cursor + 1) & 1023;
            }
            updated = now;
        }
    }

    private static int toByte(int sample) {
        return Math.max(0, Math.min(255, 128 + (sample >> 8)));
    }

    public synchronized String diagnostic(long now) {
        int peak = 0, peakLeft = 0, peakRight = 0;
        for (int i = 0; i < samples.length; i++) {
            peak = Math.max(peak, Math.abs(samples[i] - 128));
            peakLeft = Math.max(peakLeft, Math.abs(leftSamples[i] - 128));
            peakRight = Math.max(peakRight, Math.abs(rightSamples[i] - 128));
        }
        return "enabled=" + enabled + " channels=" + channels + " ageMs=" +
                (updated == 0 ? -1 : now - updated) + " peak=" + peak +
                " peakL=" + peakLeft + " peakR=" + peakRight;
    }

    public synchronized String snapshot(long now) {
        boolean fresh = enabled && updated != 0 && now - updated < 500;
        StringBuilder out = new StringBuilder(4200).append('[');
        appendArray(out, samples, fresh);
        return out.append(']').toString();
    }

    /** Butterchurn receives the real left/right PCM channels and a mono analysis buffer. */
    public synchronized String snapshotStereo(long now) {
        boolean fresh = enabled && updated != 0 && now - updated < 500;
        StringBuilder out = new StringBuilder(13000).append("{\"mix\":[");
        appendArray(out, samples, fresh);
        out.append("],\"left\":[");
        appendArray(out, leftSamples, fresh);
        out.append("],\"right\":[");
        appendArray(out, rightSamples, fresh);
        return out.append("]}").toString();
    }

    private void appendArray(StringBuilder out, int[] source, boolean fresh) {
        for (int i = 0; i < 1024; i++) {
            if (i != 0) out.append(',');
            out.append(fresh ? source[(cursor + i) & 1023] : 128);
        }
    }
}
