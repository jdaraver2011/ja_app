package com.example.ja;
public final class JaTimedCacheTest {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        long[] now = {0L};
        JaTimedCache<String, String> c = new JaTimedCache<>(2, 10, () -> now[0]);
        check(c.get("missing") == null);
        c.put("movie:1", "movie"); c.put("tv:1", "tv");
        check("movie".equals(c.get("movie:1")));
        c.put("movie:2", "second");
        check(c.get("tv:1") == null); // least recently used, distinct media keys
        now[0] = 9_999_999L;
        check("movie".equals(c.get("movie:1")));
        now[0] = 10_000_000L;
        check(c.get("movie:1") == null); // reads do not extend TTL
        c.put("movie:2", "replacement");
        now[0] = 19_000_000L;
        check("replacement".equals(c.get("movie:2")));
        now[0] = 20_000_000L;
        check(c.get("movie:2") == null);
        System.out.println("PASS: metadata isolation, LRU bound, expiry and replacement");
    }
}
