package com.example.ja;
import java.util.*;
/** Transactional movement: navigating does not persist until OK; Back leaves the stored order untouched. */
public final class JaLiveTvMoveSession {
    private List<String> draft = Collections.emptyList();
    private String channel;
    public void begin(List<String> visible, String id) {
        if (channel != null || !visible.contains(id)) return;
        draft = new ArrayList<>(new LinkedHashSet<>(visible)); channel = id;
    }
    public String channel() { return channel; }
    public List<String> order() { return new ArrayList<>(draft); }
    public void move(int direction) { if (channel != null) draft = JaLiveTvOrder.move(draft, channel, direction); }
    public void clear() { channel = null; draft = Collections.emptyList(); }
    public static List<String> displayed(List<String> saved, List<String> available) {
        Set<String> allowed = new HashSet<>(available); LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String id : saved) if (allowed.contains(id)) result.add(id);
        result.addAll(available); return new ArrayList<>(result);
    }
    /** Merge a filtered edit while keeping hidden and temporarily missing channels in their saved slots. */
    public static List<String> merge(List<String> saved, List<String> available, List<String> edited) {
        LinkedHashSet<String> all = new LinkedHashSet<>(saved); all.addAll(available); all.addAll(edited);
        Set<String> editedSet = new HashSet<>(edited);
        List<String> unique = new ArrayList<>(new LinkedHashSet<>(edited));
        if (unique.size() != edited.size()) throw new IllegalArgumentException("Invalid edited channels");
        Iterator<String> next = unique.iterator(); List<String> result = new ArrayList<>();
        for (String id : all) result.add(editedSet.contains(id) ? next.next() : id);
        return result;
    }
}
