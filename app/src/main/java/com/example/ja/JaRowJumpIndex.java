package com.example.ja;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;

/** Stable row IDs map panel choices to the displayed order, not alphabet positions. */
public final class JaRowJumpIndex {
    private final String[] ids;
    private final String[] labels;
    public JaRowJumpIndex(String[] ids, String[] labels) {
        if (ids.length != labels.length) throw new IllegalArgumentException("Mismatched rows");
        this.ids = ids.clone();
        this.labels = labels.clone();
        HashSet<String> seen = new HashSet<>();
        for (String id : ids) {
            if (id == null || id.isEmpty() || !seen.add(id))
                throw new IllegalArgumentException("Unique row IDs required");
        }
    }
    public static String initial(String label) {
        String normalized = Normalizer.normalize(label == null ? "" : label.trim(),
                Normalizer.Form.NFD).toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) return "#";
        char first = normalized.charAt(0);
        return first >= 'A' && first <= 'Z' ? String.valueOf(first) : "#";
    }
    public int rowIndex(String id) {
        for (int i = 0; i < ids.length; i++) if (ids[i].equals(id)) return i;
        return -1;
    }
    public int firstForLetter(String letter) {
        for (int i = 0; i < labels.length; i++) if (initial(labels[i]).equals(letter)) return i;
        return -1;
    }
}
