package com.example.ja;

/** Retry a lost display preference only during active video samples, with a finite budget. */
public final class JaFrameRateRecovery {
    private long nextAttemptAt;
    private long lastSampleAt = -1;
    private int attempts;

    public void requested(long now) { nextAttemptAt = now + 10_000L; }

    public boolean sample(long now, boolean matched) {
        // No samples arrive while paused. Give a resumed stream a fresh budget.
        if (lastSampleAt >= 0 && now - lastSampleAt > 3_000L) {
            attempts = 0;
            nextAttemptAt = now;
        }
        lastSampleAt = now;
        if (matched) { attempts = 0; return false; }
        if (attempts >= 3 || now < nextAttemptAt) return false;
        attempts++;
        nextAttemptAt = now + 10_000L;
        return true;
    }
}
