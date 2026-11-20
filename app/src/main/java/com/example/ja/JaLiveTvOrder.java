package com.example.ja;
import java.util.*;
/** Stable channel IDs, independent of catalog sorting or a temporary channel outage. */
public final class JaLiveTvOrder {
    private JaLiveTvOrder() {}
    public static List<String> reconcile(List<String> previous, Set<String> favorites, List<String> channels) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String id : previous) if (favorites.contains(id)) result.add(id);
        for (String id : channels) if (favorites.contains(id)) result.add(id);
        List<String> missing = new ArrayList<>(favorites); Collections.sort(missing);
        result.addAll(missing);
        return new ArrayList<>(result);
    }
    public static List<String> move(List<String> order, String id, int direction) {
        List<String> result = new ArrayList<>(order);
        int from = result.indexOf(id), to = from + Integer.signum(direction);
        if (from >= 0 && to >= 0 && to < result.size()) Collections.swap(result, from, to);
        return result;
    }
}
