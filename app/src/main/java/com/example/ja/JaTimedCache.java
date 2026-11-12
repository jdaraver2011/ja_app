package com.example.ja;

import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Bounded process-memory metadata cache. Expiry uses elapsed time, not wall time. */
public final class JaTimedCache<K, V> {
    private static final class Entry<V> {
        final V value;
        final long created;
        Entry(V value, long created) { this.value = value; this.created = created; }
    }
    private final LinkedHashMap<K, Entry<V>> entries = new LinkedHashMap<>(16, .75f, true);
    private final int capacity;
    private final long ttlNanos;
    private final LongSupplier clock;

    public JaTimedCache(int capacity, long ttlMillis) {
        this(capacity, ttlMillis, System::nanoTime);
    }
    public JaTimedCache(int capacity, long ttlMillis, LongSupplier clock) {
        if (capacity < 1 || ttlMillis < 1) throw new IllegalArgumentException("Invalid cache limits");
        this.capacity = capacity;
        this.ttlNanos = TimeUnit.MILLISECONDS.toNanos(ttlMillis);
        this.clock = clock;
    }
    public synchronized V get(K key) {
        Entry<V> entry = entries.get(key);
        if (entry == null) return null;
        if (clock.getAsLong() - entry.created >= ttlNanos) {
            entries.remove(key);
            return null;
        }
        return entry.value;
    }
    public synchronized void put(K key, V value) {
        if (value == null) throw new IllegalArgumentException("Null cache value");
        entries.put(key, new Entry<>(value, clock.getAsLong()));
        while (entries.size() > capacity) entries.remove(entries.keySet().iterator().next());
    }
}
