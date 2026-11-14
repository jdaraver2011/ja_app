package com.example.ja;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Each genre has its own ordered, bounded collection of unique title IDs. */
public final class JaGenreCatalog<T> {
    private final Map<Integer, LinkedHashMap<Integer, T>> rows = new LinkedHashMap<>();
    private final int limit;
    public JaGenreCatalog(int[] genres, int limit) {
        if (limit < 1) throw new IllegalArgumentException("Invalid row limit");
        this.limit = limit;
        for (int genre : genres) rows.put(genre, new LinkedHashMap<>());
    }
    public void add(int genre, int titleId, T title) {
        LinkedHashMap<Integer, T> row = rows.get(genre);
        if (row == null || titleId <= 0 || title == null || row.size() >= limit) return;
        row.putIfAbsent(titleId, title);
    }
    public List<T> titles(int genre) {
        Map<Integer, T> row = rows.get(genre);
        return row == null ? new ArrayList<>() : new ArrayList<>(row.values());
    }
}
