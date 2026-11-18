package com.example.ja.experiments.music;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

public final class JaMusicSourceCoordinatorTest {
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
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
    public static void main(String[] args) {
        FakePlayer p = new FakePlayer();
        JaMusicSourceCoordinator c = new JaMusicSourceCoordinator(p);
        String source = "https://media.example.org/test.m3u8";
        var old = c.begin("A", 0, true);
        var next = c.begin("B", 12_000, true);
        check(!c.resolved(old, source), "Ignore previous track result");
        check(!c.failed(old), "Ignore previous track error");
        check(c.resolved(next, source), "Accept current source");
        check(p.events.equals(List.of("stop", "stop", "prepare:B:12000", "play")),
                "Prepare with resume position before starting");
        int count = p.events.size();
        check(!c.resolved(next, source) && p.events.size() == count, "Ignore duplicate callback");
        var olderSameTrack = c.begin("B", 0, true);
        var newerSameTrack = c.begin("B", 5_000, true);
        check(!c.resolved(olderSameTrack, source), "Generation isolates same-track requests");
        c.pause();
        check(c.resolved(newerSameTrack, source), "Resolve after pause");
        check(p.events.get(p.events.size() - 1).equals("pause"), "Do not autoplay after pause");
        var videoRace = c.begin("C", 0, true);
        c.setVideoActive(true);
        check(c.resolved(videoRace, source), "Resolve while video active");
        count = p.events.size();
        c.play();
        c.setVideoActive(false);
        check(p.events.size() == count, "Video gate prevents play and automatic resume");
        c.play();
        check(p.events.get(p.events.size() - 1).equals("play"), "Explicit resume works");
        var cancelled = c.begin("D", 0, true);
        c.stop();
        check(!c.resolved(cancelled, source), "Stop invalidates in-flight source");
        var unsafe = c.begin("E", 0, true);
        check(!c.resolved(unsafe, "javascript:alert(1)")
                && c.state() == JaMusicSourceCoordinator.State.FAILED, "Reject invalid source");
        p.failPrepare = true;
        var broken = c.begin("F", 0, true);
        check(!c.resolved(broken, source)
                && c.state() == JaMusicSourceCoordinator.State.FAILED, "Handle decoder failure");
        System.out.println("Music source-handoff race tests passed (fake player; no Deezer playback)");
    }
}
