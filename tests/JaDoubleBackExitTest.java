package com.example.ja;
public final class JaDoubleBackExitTest {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        JaDoubleBackExit gate = new JaDoubleBackExit();
        check(!gate.press(0));
        check(gate.press(2000)); // inclusive two-second window, even early after boot
        check(!gate.press(3000)); // confirmed exit does not leave an armed gate
        check(!gate.press(5001)); // expired: prompt again
        check(gate.press(5100));
        check(!gate.press(6000));
        gate.reset(); // details navigation / Home / tab change
        check(!gate.press(6100));
        gate.reset();
        check(!gate.press(6200));
        check(gate.press(6300));
        System.out.println("PASS: first Back, confirmation, expiry, reset on navigation/background and fresh launch");
    }
}
