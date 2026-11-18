package com.example.ja.experiments.music;

import java.net.URI;

/** Isolated source-handoff prototype. Call on one owner thread. No Deezer API implementation. */
public final class JaMusicSourceCoordinator {
    public interface Player {
        void prepare(String trackId, URI source, long positionMs);
        void play();
        void pause();
        void stop();
    }
    public enum State { IDLE, RESOLVING, READY, FAILED }
    public static final class Request {
        public final String trackId;
        public final long generation;
        private final long positionMs;
        private Request(String trackId, long generation, long positionMs) {
            this.trackId = trackId;
            this.generation = generation;
            this.positionMs = positionMs;
        }
    }
    private final Player player;
    private Request pending;
    private State state = State.IDLE;
    private long generation;
    private boolean wantsPlay;
    private boolean videoActive;

    public JaMusicSourceCoordinator(Player player) {
        if (player == null) throw new IllegalArgumentException("Player required");
        this.player = player;
    }
    public State state() { return state; }

    /** Return this token with the asynchronous source result; never log source URLs. */
    public Request begin(String trackId, long positionMs, boolean autoplay) {
        if (trackId == null || trackId.trim().isEmpty() || positionMs < 0)
            throw new IllegalArgumentException("Track and nonnegative position required");
        pending = new Request(trackId, ++generation, positionMs);
        state = State.RESOLVING;
        wantsPlay = autoplay && !videoActive;
        player.stop();
        return pending;
    }
    /** Accept only the pending request, including when the same track is requested twice. */
    public boolean resolved(Request request, String sourceUrl) {
        if (request == null || request != pending || state != State.RESOLVING) return false;
        URI source;
        try {
            source = new URI(sourceUrl);
            if (!"https".equalsIgnoreCase(source.getScheme()) || source.getHost() == null
                    || source.getRawUserInfo() != null) throw new IllegalArgumentException();
        } catch (Exception invalid) {
            failed(request);
            return false;
        }
        pending = null;
        try {
            player.prepare(request.trackId, source, request.positionMs);
            state = State.READY;
            if (wantsPlay && !videoActive) player.play();
            else player.pause();
            return true;
        } catch (RuntimeException failure) {
            state = State.FAILED;
            wantsPlay = false;
            player.stop();
            return false;
        }
    }
    public boolean failed(Request request) {
        if (request == null || request != pending || state != State.RESOLVING) return false;
        pending = null;
        state = State.FAILED;
        wantsPlay = false;
        player.stop();
        return true;
    }
    public void pause() {
        wantsPlay = false;
        if (state == State.READY) player.pause();
    }
    public void play() {
        if (videoActive || (state != State.RESOLVING && state != State.READY)) return;
        wantsPlay = true;
        if (state == State.READY) player.play();
    }
    public void setVideoActive(boolean active) {
        videoActive = active;
        if (active) pause();
        // User must explicitly resume music after video playback ends.
    }
    public void stop() {
        pending = null;
        ++generation;
        wantsPlay = false;
        state = State.IDLE;
        player.stop();
    }
}
