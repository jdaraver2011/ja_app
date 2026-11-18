package com.example.ja;

public final class JaRowJumpIndexTest {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        JaRowJumpIndex rows = new JaRowJumpIndex(
            new String[] {"abc", "apple", "bbc", "hbo", "numeric", "accent"},
            new String[] {"ABC", "Apple TV+", "BBC", "HBO", "24 Network", "Évasion"});
        check(rows.firstForLetter("A") == 0);
        check(rows.rowIndex("apple") == 1); // Exact row differs from the letter's first match.
        check(rows.firstForLetter("Z") == -1);
        check(rows.firstForLetter("#") == 4);
        check(rows.firstForLetter("E") == 5);
        check(rows.rowIndex("missing") == -1);
        check(JaRowJumpIndex.initial("  hbo").equals("H"));
        check(JaRowJumpIndex.initial(null).equals("#"));
        check(JaRowJumpIndex.initial("").equals("#"));
        JaRowJumpIndex reordered = new JaRowJumpIndex(new String[]{"hbo", "abc"}, new String[]{"HBO", "ABC"});
        check(reordered.rowIndex("abc") == 1);
        check(reordered.firstForLetter("A") == 1);
        check(new JaRowJumpIndex(new String[0], new String[0]).firstForLetter("A") == -1);
        boolean duplicateRejected = false;
        try { new JaRowJumpIndex(new String[]{"x", "x"}, new String[]{"ABC", "BBC"}); }
        catch (IllegalArgumentException expected) { duplicateRejected = true; }
        check(duplicateRejected);
        System.out.println("Row jump indexing tests passed");
    }
}
