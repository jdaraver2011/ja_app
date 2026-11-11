package com.example.ja;

import java.util.concurrent.atomic.AtomicInteger;

public final class JaStartupBarrierTest {
    private static final class Probe implements JaStartupBarrier.Listener {
        final AtomicInteger loads = new AtomicInteger();
        final AtomicInteger failures = new AtomicInteger();
        @Override public void onReady() { loads.incrementAndGet(); }
        @Override public void onFailure(String stage, String reason) { failures.incrementAndGet(); }
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static JaStartupBarrier gate(Probe probe) {
        return new JaStartupBarrier(probe, "extensions", "blocker", "monitors");
    }
    public static void main(String[] args) throws Exception {
        String[][] orders = {
            {"extensions", "blocker", "monitors"}, {"extensions", "monitors", "blocker"},
            {"blocker", "extensions", "monitors"}, {"blocker", "monitors", "extensions"},
            {"monitors", "extensions", "blocker"}, {"monitors", "blocker", "extensions"}
        };
        for (String[] order : orders) {
            Probe p = new Probe(); JaStartupBarrier g = gate(p);
            g.complete(order[0]);g.complete(order[1]);
            check(p.loads.get() == 0, "Loaded while setup still running");
            g.complete(order[2]);
            check(p.loads.get() == 1, "Did not load exactly once when ready");
            g.complete(order[2]);g.fail("late", "late error");
            check(p.loads.get() == 1 && p.failures.get() == 0, "Late event changed completed startup");
        }
        {
            Probe p = new Probe();JaStartupBarrier g = gate(p);
            g.complete("extensions");g.complete("monitors");
            // This is the old 15-second timeout condition: a blocker is still
            // installing. No page may load until the outstanding stage succeeds.
            check(g.pendingStages().contains("blocker") && p.loads.get() == 0, "Blocker was bypassed");
            g.fail("deadline", "offline install");
            g.complete("blocker");
            check(p.loads.get() == 0 && p.failures.get() == 1, "Late install loaded after timeout");
        }
        {
            Probe p = new Probe();JaStartupBarrier g = gate(p);
            g.complete("extensions");g.fail("blocker", "download failed");
            g.complete("monitors");g.complete("blocker");g.fail("again", "duplicate");
            check(p.loads.get() == 0 && p.failures.get() == 1, "Failure did not prevent loading");
        }
        {
            Probe p = new Probe();JaStartupBarrier g = gate(p);
            g.complete("extensions");g.cancel();g.complete("blocker");g.complete("monitors");g.fail("late", "disposed");
            check(p.loads.get() == 0 && p.failures.get() == 0, "Closed player received callback");
            JaStartupBarrier retry = gate(p);
            for (String stage : orders[0]) retry.complete(stage);
            check(p.loads.get() == 1, "Fresh retry inherited cancelled state");
        }
        {
            Probe p = new Probe();JaStartupBarrier g = gate(p);
            Thread[] workers = new Thread[3];
            for(int i=0; i<3; i++) {
                final String stage=orders[0][i];
                workers[i]=new Thread(()->g.complete(stage)); workers[i].start();
            }
            for(Thread worker:workers) worker.join();
            check(p.loads.get()==1 && p.failures.get()==0,"Concurrent callbacks loaded more than once");
        }
        System.out.println("PASS: six startup orders, slow blocker, timeout, failure, disposal, retry and concurrent callbacks");
    }
}
