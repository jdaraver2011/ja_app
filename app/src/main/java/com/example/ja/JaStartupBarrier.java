package com.example.ja;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** One session may load only after every startup stage succeeds. */
public final class JaStartupBarrier {
    public interface Listener {
        void onReady();
        void onFailure(String stage, String reason);
    }
    private final Set<String> pending;
    private Listener listener;
    private boolean terminal;

    public JaStartupBarrier(Listener listener, String... stages) {
        this.listener = listener;
        this.pending = new HashSet<>(Arrays.asList(stages));
        if (pending.isEmpty()) throw new IllegalArgumentException("Startup stages required");
    }
    public synchronized boolean isTerminal() { return terminal; }
    public synchronized String pendingStages() { return pending.toString(); }
    public void complete(String stage) {
        Listener ready = null;
        synchronized (this) {
            if (terminal || !pending.remove(stage)) return;
            if (pending.isEmpty()) {
                terminal = true;
                ready = listener;
                listener = null;
            }
        }
        if (ready != null) ready.onReady();
    }
    public void fail(String stage, String reason) {
        Listener failed;
        synchronized (this) {
            if (terminal) return;
            terminal = true;
            failed = listener;
            listener = null;
        }
        if (failed != null) failed.onFailure(stage, reason);
    }
    public synchronized void cancel() {
        terminal = true;
        listener = null;
    }
}
