package com.example.ja;
public final class JaFrameRateRecoveryTest {
    static void check(boolean v) { if (!v) throw new AssertionError(); }
    public static void main(String[] args) {
        JaFrameRateRecovery r = new JaFrameRateRecovery();
        r.requested(0);
        for (long t=1000;t<10000;t+=1000) check(!r.sample(t,false));
        check(r.sample(10000,false));
        for (long t=11000;t<20000;t+=1000) check(!r.sample(t,false));
        check(r.sample(20000,false));
        for (long t=21000;t<30000;t+=1000) check(!r.sample(t,false));
        check(r.sample(30000,false));
        for (long t=31000;t<=45000;t+=1000) check(!r.sample(t,false));
        check(!r.sample(46000,true));
        check(r.sample(47000,false)); // matched mode clears the exhausted budget
        check(!r.sample(48000,true));
        check(r.sample(60000,false)); // pause gap: immediate recovery on resume
        check(!r.sample(61000,false));
        System.out.println("PASS: initial switch grace, retry throttle/budget, recovery success and pause/resume");
    }
}
