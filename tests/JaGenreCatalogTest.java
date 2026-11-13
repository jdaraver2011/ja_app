package com.example.ja;
import java.util.List;
public final class JaGenreCatalogTest {
    private static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) {
        // A title shared by Action and Comedy must stay in both, in query order.
        JaGenreCatalog<String> rows = new JaGenreCatalog<>(new int[]{28,35,18}, 3);
        rows.add(28, 42, "Shared"); rows.add(35, 7, "Comedy first");
        rows.add(35, 42, "Shared"); rows.add(28, 42, "Repeat on next page");
        check(rows.titles(28).equals(List.of("Shared")));
        check(rows.titles(35).equals(List.of("Comedy first", "Shared")));
        rows.add(28, 8, "Second"); rows.add(28, 9, "Third"); rows.add(28, 10, "Beyond cap");
        check(rows.titles(28).equals(List.of("Shared", "Second", "Third")));
        // A full row cannot prevent this same title from entering another genre.
        rows.add(18, 10, "Beyond cap"); check(rows.titles(18).equals(List.of("Beyond cap")));
        rows.add(999,42,"Unknown genre"); rows.add(18,0,"Invalid ID");
        check(rows.titles(18).size()==1);
        rows.titles(35).clear(); check(rows.titles(35).size()==2);
        // TV categories use exactly the same independent collection rule.
        JaGenreCatalog<String> tv = new JaGenreCatalog<>(new int[]{10759,35},50);
        tv.add(10759,42,"Shared show"); tv.add(35,42,"Shared show");
        check(tv.titles(10759).equals(tv.titles(35)));
        System.out.println("PASS: shared movie/TV genre membership, per-row uniqueness, independent limits and query order");
    }
}
