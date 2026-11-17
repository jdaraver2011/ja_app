package com.example.ja;
import java.util.Set;

public final class JaNowPlayingBatchTest {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    private static void reject(Runnable task) {
        try { task.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid snapshot accepted");
    }
    public static void main(String[] args) {
        JaNowPlayingBatch batch = new JaNowPlayingBatch();
        check(batch.hasNext() && !batch.complete());
        batch.accept(1, 2, 3, new int[]{10, 20});
        check(!batch.complete() && batch.nextPage() == 2);
        batch.accept(2, 2, 3, new int[]{30});
        check(batch.complete() && !batch.hasNext());
        JaNowPlayingBatch partial = new JaNowPlayingBatch();
        partial.accept(1, 2, 3, new int[]{10, 20});
        reject(() -> partial.accept(2, 3, 4, new int[]{30}));
        check(!partial.complete());
        JaNowPlayingBatch duplicate = new JaNowPlayingBatch();
        duplicate.accept(1, 2, 2, new int[]{10});
        reject(() -> duplicate.accept(2, 2, 2, new int[]{10}));
        JaNowPlayingBatch missing = new JaNowPlayingBatch();
        missing.accept(1, 1, 2, new int[]{10});
        check(!missing.complete());
        JaNowPlayingBatch empty = new JaNowPlayingBatch();
        empty.accept(1, 0, 0, new int[]{});
        check(empty.complete());
        reject(() -> new JaNowPlayingBatch().accept(2, 2, 1, new int[]{1}));
        check(!JaNowPlayingBatch.includeOutside("movie", 10, true, Set.of(10)));
        check(JaNowPlayingBatch.includeOutside("movie", 10, true, Set.of(20)));
        check(!JaNowPlayingBatch.includeOutside("movie", 10, false, Set.of()));
        check(JaNowPlayingBatch.includeOutside("tv", 10, false, Set.of(10)));
        System.out.println("Now Playing completeness and routing tests passed");
    }
}
