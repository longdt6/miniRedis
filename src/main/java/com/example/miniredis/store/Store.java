package com.example.miniredis.store;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class Store {

    private final ConcurrentHashMap<String, Item> data = new ConcurrentHashMap<>();
    private final Clock clock = Clock.systemUTC();

    public Optional<String> get(String key) {
        Item item = data.get(key);
        if (item == null) {
            return Optional.empty();
        }
        if (item.hasExpiry() && isExpired(item)) {
            data.remove(key, item);
            return Optional.empty();
        }
        return Optional.ofNullable(item.value());
    }

    public void set(String key, String value, Duration ttl) {
        if (ttl == null) {
            data.put(key, Item.withoutExpiry(value));
        } else {
            long expiresAt = Instant.now(clock).plus(ttl).toEpochMilli();
            data.put(key, Item.withExpiry(value, expiresAt));
        }
    }

    public boolean del(String key) {
        return data.remove(key) != null;
    }

    public long exists(String key) {
        return get(key).isPresent() ? 1L : 0L;
    }

    public long ttl(String key) {
        Item item = data.get(key);
        if (item == null) {
            return -2L;
        }
        if (!item.hasExpiry()) {
            return -1L;
        }
        long remainingMs = item.expiresAtEpochMs() - Instant.now(clock).toEpochMilli();
        if (remainingMs <= 0) {
            data.remove(key, item);
            return -2L;
        }
        return Math.max(1L, (remainingMs + 999) / 1000);
    }

    public boolean setExpiry(String key, long seconds) {
        Item item = data.get(key);
        if (item == null) {
            return false;
        }
        long expiresAt = Instant.now(clock).plusSeconds(seconds).toEpochMilli();
        data.put(key, Item.withExpiry(item.value(), expiresAt));
        return true;
    }

    public long incr(String key) throws NumberFormatException {
        Item item = data.get(key);
        long current = 0L;
        if (item != null) {
            if (isExpired(item)) {
                data.remove(key, item);
            } else {
                current = Long.parseLong(item.value());
            }
        }
        long next = current + 1;
        data.put(key, Item.withoutExpiry(Long.toString(next)));
        return next;
    }

    public List<String> keys() {
        List<String> result = new ArrayList<>(data.size());
        for (String key : data.keySet()) {
            if (get(key).isPresent()) {
                result.add(key);
            }
        }
        return result;
    }

    public void flushAll() {
        data.clear();
    }

    public Map<String, Item> snapshot() {
        Map<String, Item> snap = new LinkedHashMap<>();
        for (var entry : data.entrySet()) {
            if (!isExpired(entry.getValue())) {
                snap.put(entry.getKey(), entry.getValue());
            }
        }
        return snap;
    }

    public void load(Map<String, Item> items) {
        long now = Instant.now(clock).toEpochMilli();
        for (var entry : items.entrySet()) {
            Item item = entry.getValue();
            if (item.hasExpiry() && item.expiresAtEpochMs() != null && item.expiresAtEpochMs() <= now) {
                continue;
            }
            data.put(entry.getKey(), item);
        }
    }

    public int size() {
        return data.size();
    }

    public void removeExpired() {
        for (var entry : data.entrySet()) {
            if (isExpired(entry.getValue())) {
                data.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    private boolean isExpired(Item item) {
        return item.hasExpiry() && item.expiresAtEpochMs() != null
                && item.expiresAtEpochMs() <= Instant.now(clock).toEpochMilli();
    }
}
