package com.example.miniredis.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StoreTest {

    private Store store;

    @BeforeEach
    void setUp() {
        store = new Store();
    }

    @Test
    void setGetRoundTrip() {
        store.set("foo", "bar", null);
        assertEquals("bar", store.get("foo").orElse(null));
    }

    @Test
    void delRemovesKey() {
        store.set("k", "v", null);
        assertTrue(store.del("k"));
        assertFalse(store.del("k"));
    }

    @Test
    void existsCounts() {
        store.set("a", "1", null);
        store.set("b", "2", Duration.ofSeconds(60));
        assertEquals(1L, store.exists("a"));
        assertEquals(1L, store.exists("b"));
        assertEquals(0L, store.exists("nope"));
    }

    @Test
    void incrAddsOne() {
        assertEquals(1L, store.incr("counter"));
        assertEquals(2L, store.incr("counter"));
        assertEquals(3L, store.incr("counter"));
    }

    @Test
    void incrFromExistingValue() {
        store.set("c", "10", null);
        assertEquals(11L, store.incr("c"));
    }

    @Test
    void incrNotAnIntegerThrows() {
        store.set("c", "abc", null);
        assertThrows(NumberFormatException.class, () -> store.incr("c"));
    }

    @Test
    void expire_lazyExpiryOnGet() throws Exception {
        store.set("k", "v", Duration.ofMillis(50));
        assertTrue(store.get("k").isPresent());
        Thread.sleep(120);
        assertTrue(store.get("k").isEmpty());
    }

    @Test
    void expire_tickerSweep() throws Exception {
        store.set("k", "v", Duration.ofMillis(50));
        assertTrue(store.get("k").isPresent());
        Thread.sleep(120);
        store.removeExpired();
        assertEquals(0, store.size());
    }

    @Test
    void ttl_noExpiry() {
        store.set("k", "v", null);
        assertEquals(-1L, store.ttl("k"));
    }

    @Test
    void ttl_missing() {
        assertEquals(-2L, store.ttl("nope"));
    }

    @Test
    void ttl_withExpiry() {
        store.set("k", "v", Duration.ofSeconds(60));
        long ttl = store.ttl("k");
        assertTrue(ttl > 0 && ttl <= 60, "ttl in (0,60], got " + ttl);
    }

    @Test
    void setExpiry() {
        store.set("k", "v", null);
        assertTrue(store.setExpiry("k", 30));
        long ttl = store.ttl("k");
        assertTrue(ttl > 0 && ttl <= 30, "ttl in (0,30], got " + ttl);
        assertFalse(store.setExpiry("nope", 30));
    }

    @Test
    void keysAndFlush() {
        store.set("a", "1", null);
        store.set("b", "2", null);
        assertEquals(2, store.keys().size());
        store.flushAll();
        assertEquals(0, store.keys().size());
    }

    @Test
    void snapshotAndLoad() {
        store.set("a", "1", null);
        store.set("b", "2", Duration.ofSeconds(60));
        Map<String, Item> snap = store.snapshot();
        assertEquals(2, snap.size());

        Store fresh = new Store();
        fresh.load(snap);
        assertEquals("1", fresh.get("a").orElse(null));
        assertEquals("2", fresh.get("b").orElse(null));
    }

    @Test
    void loadSkipsExpired() throws Exception {
        store.set("alive", "yes", Duration.ofSeconds(60));
        store.set("dead", "no", Duration.ofMillis(10));
        Thread.sleep(40);

        Map<String, Item> snap = store.snapshot();
        assertEquals(1, snap.size());
        assertTrue(snap.containsKey("alive"));
    }
}
