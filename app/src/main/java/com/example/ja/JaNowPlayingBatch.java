package com.example.ja;

import java.util.HashSet;
import java.util.Set;

/** A partial or inconsistent page download must never promote removed titles. */
public final class JaNowPlayingBatch {
    private int nextPage = 1;
    private int pages = -1;
    private int total = -1;
    private final Set<Integer> ids = new HashSet<>();

    public void accept(int page, int pageCount, int resultCount, int[] pageIds) {
        int normalizedPages = Math.max(1, pageCount);
        if (page != nextPage || pageCount < 0 || normalizedPages > 500 || resultCount < 0
                || (pages >= 0 && (pages != normalizedPages || total != resultCount))) {
            throw new IllegalArgumentException("Inconsistent Now Playing pages");
        }
        if (pages < 0) { pages = normalizedPages; total = resultCount; }
        if (page > pages || (resultCount > 0 && pageIds.length == 0))
            throw new IllegalArgumentException("Missing Now Playing page");
        for (int id : pageIds) {
            if (id <= 0 || !ids.add(id)) throw new IllegalArgumentException("Invalid/duplicate movie ID");
        }
        nextPage++;
    }

    public boolean hasNext() { return pages < 0 || nextPage <= pages; }
    public int nextPage() { return nextPage; }
    public boolean complete() { return pages >= 0 && !hasNext() && ids.size() == total; }

    public static boolean includeOutside(String type, int id, boolean ready, Set<Integer> current) {
        return !"movie".equals(type) || (ready && !current.contains(id));
    }
}
