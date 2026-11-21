package com.example.ja;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Bounded, allocation-free audio-thread capture. Never mutates the decoder's buffer. */
public final class JaMusicPcm {
    private final int[] samples = new int[1024];
    private int cursor, channels = 2;
    private volatile boolean enabled;
    private long updated;
    public JaMusicPcm() { Arrays.fill(samples, 128); }
    public synchronized void enable(boolean value) { enabled = value; cursor = 0; updated = 0; Arrays.fill(samples, 128); }
    public synchronized void format(int channelCount) { channels = Math.max(1, channelCount); cursor = 0; updated = 0; Arrays.fill(samples, 128); }
    public void capture16(ByteBuffer buffer, long now) {
        if (!enabled) return;
        synchronized (this) {
            int step = channels * 2;
            for (int p = buffer.position(); p + step <= buffer.limit(); p += step) {
                int total = 0;
                for (int c = 0; c < channels; c++) {
                    int at = p + c * 2;
                    total += (short) ((buffer.get(at) & 255) | (buffer.get(at + 1) << 8));
                }
                samples[cursor] = Math.max(0, Math.min(255, 128 + (total / channels >> 8)));
                cursor = (cursor + 1) & 1023;
            }
            updated = now;
        }
    }
    public synchronized String diagnostic(long now) {
        int peak = 0;
        for (int sample : samples) peak = Math.max(peak, Math.abs(sample - 128));
        return "enabled=" + enabled + " channels=" + channels + " ageMs=" + (updated == 0 ? -1 : now - updated) + " peak=" + peak;
    }
    public synchronized String snapshot(long now) {
        StringBuilder out = new StringBuilder(4200).append('[');
        boolean fresh = enabled && updated != 0 && now - updated < 500;
        for (int i = 0; i < 1024; i++) { if (i != 0) out.append(','); out.append(fresh ? samples[(cursor + i) & 1023] : 128); }
        return out.append(']').toString();
    }
}
