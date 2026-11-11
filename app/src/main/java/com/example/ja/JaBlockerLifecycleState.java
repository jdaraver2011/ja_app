package com.example.ja;

import java.util.HashSet;
import java.util.Set;

/** Tracks the latest requested selection independently from native callbacks. */
public final class JaBlockerLifecycleState {
    private String desired;
    private long generation;
    private final Set<String> started = new HashSet<>();
    public synchronized long request(String mode) {
        if (!mode.equals(desired)) { desired = mode; generation++; }
        return generation;
    }
    public synchronized boolean isCurrent(long token, String mode) {
        return token == generation && mode.equals(desired);
    }
    public synchronized String desiredMode() { return desired; }
    public synchronized void starting(String id) { started.remove(id); }
    public synchronized void ready(String id) { started.add(id); }
    public synchronized void stopped(String id) { started.remove(id); }
    public synchronized boolean hasStarted(String id) { return started.contains(id); }
}
