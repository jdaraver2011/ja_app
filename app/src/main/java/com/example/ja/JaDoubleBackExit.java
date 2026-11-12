package com.example.ja;

/** Two root-level Back presses within two seconds; navigation resets the prompt. */
public final class JaDoubleBackExit {
    private long firstPress = -1L;
    public boolean press(long now) {
        if (firstPress >= 0 && now >= firstPress && now - firstPress <= 2_000L) {
            reset();
            return true;
        }
        firstPress = now;
        return false;
    }
    public void reset() { firstPress = -1L; }
}
