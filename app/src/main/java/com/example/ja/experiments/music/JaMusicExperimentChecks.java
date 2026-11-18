package com.example.ja.experiments.music;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.function.Consumer;

public final class JaMusicExperimentChecks {
    private static final class FakePlayer implements JaMusicSourceCoordinator.Player {
        final List<String> events = new ArrayList<>();
        boolean failPrepare;
        public void prepare(String track, URI source, long position) {
            if (failPrepare) throw new IllegalStateException("Simulated decoder failure");
            events.add("prepare:" + track + ":" + position);
        }
        public void play() { events.add("play"); }
        public void pause() { events.add("pause"); }
        public void stop() { events.add("stop"); }
    }
    private static void check(boolean condition, String label, Consumer<String> logger) {
        if (!condition) throw new AssertionError(label);
        logger.accept("CHECK_PASS name=" + label);
    }
    public static String run(Consumer<String> logger) {
        logger.accept("RUN_BEGIN mode=SIMULATED network=false audio=false");
        FakePlayer p = new FakePlayer();
        JaMusicSourceCoordinator c = new JaMusicSourceCoordinator(p);
        String source = "https://media.example.org/test.m3u8";
        var old = c.begin("A", 0, true);
        var next = c.begin("B", 12_000, true);
        check(!c.resolved(old, source), "Ignore previous track result", logger);
        check(!c.failed(old), "Ignore previous track error", logger);
        check(c.resolved(next, source), "Accept current source", logger);
        check(p.events.equals(Arrays.asList("stop", "stop", "prepare:B:12000", "play")),
                "Prepare with resume position before starting", logger);
        int count = p.events.size();
        check(!c.resolved(next, source) && p.events.size() == count, "Ignore duplicate callback", logger);
        var olderSameTrack = c.begin("B", 0, true);
        var newerSameTrack = c.begin("B", 5_000, true);
        check(!c.resolved(olderSameTrack, source), "Generation isolates same-track requests", logger);
        c.pause();
        check(c.resolved(newerSameTrack, source), "Resolve after pause", logger);
        check(p.events.get(p.events.size() - 1).equals("pause"), "Do not autoplay after pause", logger);
        var videoRace = c.begin("C", 0, true);
        c.setVideoActive(true);
        check(c.resolved(videoRace, source), "Resolve while video active", logger);
        count = p.events.size();
        c.play();
        c.setVideoActive(false);
        check(p.events.size() == count, "Video gate prevents play and automatic resume", logger);
        c.play();
        check(p.events.get(p.events.size() - 1).equals("play"), "Explicit resume works", logger);
        var cancelled = c.begin("D", 0, true);
        c.stop();
        check(!c.resolved(cancelled, source), "Stop invalidates in-flight source", logger);
        var unsafe = c.begin("E", 0, true);
        check(!c.resolved(unsafe, "javascript:alert(1)")
                && c.state() == JaMusicSourceCoordinator.State.FAILED, "Reject invalid source", logger);
        p.failPrepare = true;
        var broken = c.begin("F", 0, true);
        check(!c.resolved(broken, source)
                && c.state() == JaMusicSourceCoordinator.State.FAILED, "Handle decoder failure", logger);
        logger.accept("RUN_COMPLETE mode=SIMULATED result=PASS nativeDeezerPlayback=false");
        return "All simulated Music checks passed. No Deezer streaming was tested.";
    }
    public static void main(String[] args) {
        System.out.println(run(System.out::println));
    }
}
