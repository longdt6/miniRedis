package com.example.miniredis.store;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class Store {

    private final Map<String, Item> data = new LinkedHashMap<>();
    private final Clock clock = Clock.systemUTC();
    private ExecutorService worker;
    private AofLog aof;

    private static final System.Logger LOG = System.getLogger(Store.class.getName());

    @PostConstruct
    void start() {
        worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "miniredis-cmd");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void stop() throws InterruptedException {
        if (worker != null) {
            worker.shutdown();
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                worker.shutdownNow();
            }
        }
    }

    /**
     * Installs the append-only log. Mutating commands append to it; all appends
     * run on the single worker thread, so the log is written in command order.
     */
    public void setAof(AofLog aof) {
        this.aof = aof;
    }

    public Optional<String> get(String key) {
        return run(() -> {
            Item item = data.get(key);
            if (item == null) {
                return Optional.<String>empty();
            }
            if (isExpired(item)) {
                data.remove(key);
                return Optional.<String>empty();
            }
            return Optional.ofNullable(item.value());
        });
    }

    public void set(String key, String value, Duration ttl) {
        run(() -> {
            Long absDeadlineMs = null;
            if (ttl == null) {
                data.put(key, Item.withoutExpiry(value));
            } else {
                absDeadlineMs = Instant.now(clock).plus(ttl).toEpochMilli();
                data.put(key, Item.withExpiry(value, absDeadlineMs));
            }
            // AOF: canonicalize. Logging "EX <relative>" would replay to a
            // different deadline; log the absolute ms under PXAT instead.
            appendAofSet(key, value, absDeadlineMs);
            return null;
        });
    }

    public boolean del(String key) {
        return run(() -> {
            boolean removed = data.remove(key) != null;
            if (removed) {
                aofAppend("DEL", key);
            }
            return removed;
        });
    }

    public long exists(String key) {
        return get(key).isPresent() ? 1L : 0L;
    }

    public long ttl(String key) {
        return run(() -> {
            Item item = data.get(key);
            if (item == null) {
                return -2L;
            }
            if (!item.hasExpiry()) {
                return -1L;
            }
            long remainingMs = item.expiresAtEpochMs() - Instant.now(clock).toEpochMilli();
            if (remainingMs <= 0) {
                data.remove(key);
                return -2L;
            }
            return Math.max(1L, (remainingMs + 999) / 1000);
        });
    }

    public boolean setExpiry(String key, long seconds) {
        return run(() -> setExpiryAtInternal(key, Instant.now(clock).plusSeconds(seconds).toEpochMilli()));
    }

    /**
     * Sets an absolute deadline. {@link ExpiryCommands#pexpireat} replays from
     * AOF through this; the {@code EXPIRE} command delegates by computing
     * {@code now + seconds} in {@link #setExpiry}.
     */
    public boolean setExpiryAt(String key, long expiresAtEpochMs) {
        return run(() -> setExpiryAtInternal(key, expiresAtEpochMs));
    }

    /** Shared by {@link #setExpiry} and {@link #setExpiryAt}; runs on the worker thread. */
    private boolean setExpiryAtInternal(String key, long expiresAtEpochMs) {
        Item item = data.get(key);
        if (item == null) {
            return false;
        }
        data.put(key, Item.withExpiry(item.value(), expiresAtEpochMs));
        aofAppend("PEXPIREAT", key, Long.toString(expiresAtEpochMs));
        return true;
    }

    public long incr(String key) throws NumberFormatException {
        try {
            return run(() -> {
                long current = 0L;
                Item item = data.get(key);
                if (item != null && !isExpired(item)) {
                    current = Long.parseLong(item.value());
                }
                long next = current + 1;
                data.put(key, Item.withoutExpiry(Long.toString(next)));
                aofAppend("INCR", key);
                return next;
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof NumberFormatException nfe) {
                throw nfe;
            }
            throw e;
        }
    }

    public List<String> keys() {
        return run(() -> {
            List<String> result = new ArrayList<>(data.size());
            for (String key : new ArrayList<>(data.keySet())) {
                Item item = data.get(key);
                if (item != null && !isExpired(item)) {
                    result.add(key);
                }
            }
            return result;
        });
    }

    public void flushAll() {
        run(() -> {
            data.clear();
            aofAppend("FLUSHALL");
            return null;
        });
    }

    public Map<String, Item> snapshot() {
        return run(() -> {
            Map<String, Item> snap = new LinkedHashMap<>();
            for (var entry : data.entrySet()) {
                if (!isExpired(entry.getValue())) {
                    snap.put(entry.getKey(), entry.getValue());
                }
            }
            return snap;
        });
    }

    public void load(Map<String, Item> items) {
        run(() -> {
            long now = Instant.now(clock).toEpochMilli();
            for (var entry : items.entrySet()) {
                Item item = entry.getValue();
                if (item.hasExpiry() && item.expiresAtEpochMs() != null && item.expiresAtEpochMs() <= now) {
                    continue;
                }
                data.put(entry.getKey(), item);
            }
            return null;
        });
    }

    public int size() {
        return run(() -> data.size());
    }

    public void removeExpired() {
        run(() -> {
            List<String> expiredKeys = new ArrayList<>();
            for (var entry : data.entrySet()) {
                if (isExpired(entry.getValue())) {
                    expiredKeys.add(entry.getKey());
                }
            }
            for (String key : expiredKeys) {
                data.remove(key);
            }
            return null;
        });
    }

    private boolean isExpired(Item item) {
        return item.hasExpiry() && item.expiresAtEpochMs() != null
                && item.expiresAtEpochMs() <= Instant.now(clock).toEpochMilli();
    }

    private void appendAofSet(String key, String value, Long expiresAtEpochMs) {
        if (expiresAtEpochMs == null) {
            aofAppend("SET", key, value);
        } else {
            // PXAT is the absolute-time variant of SET (Redis 6.2+).
            aofAppend("SET", key, value, "PXAT", Long.toString(expiresAtEpochMs));
        }
    }

    private void aofAppend(String cmd, String... args) {
        AofLog log = aof;
        if (log == null) {
            return;
        }
        try {
            log.append(cmd, List.of(args));
        } catch (IOException e) {
            // A disk error must not fail the in-memory mutation: the store
            // remains consistent; only the durability tail is lost.
            LOG.log(System.Logger.Level.WARNING, "AOF append failed for " + cmd, e);
        }
    }

    private void ensureWorker() {
        if (worker == null) {
            synchronized (this) {
                if (worker == null) {
                    start();
                }
            }
        }
    }

    private <T> T run(Callable<T> task) {
        ensureWorker();
        try {
            return worker.submit(task).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while running store task", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new RuntimeException(cause);
        }
    }
}