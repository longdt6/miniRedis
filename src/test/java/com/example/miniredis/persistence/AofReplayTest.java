package com.example.miniredis.persistence;

import com.example.miniredis.store.AofLog;
import com.example.miniredis.store.AofReplay;
import com.example.miniredis.store.Store;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * End-to-end: append mutations through a Store with an AOF, close, read the
 * tail, replay it onto a fresh store, and verify the state matches.
 */
class AofReplayTest {

    @Test
    void replayReconstructsState(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("appendonly.aof");

        Store store = new Store();
        AofLog aof = AofLog.open(file, false);
        store.setAof(aof);

        store.set("a", "1", null);
        store.set("b", "2", Duration.ofSeconds(60));
        store.incr("counter");
        store.incr("counter");
        store.del("a");
        store.setExpiry("b", 120);

        aof.close();

        // Sanity: the on-disk log must contain canonical absolute deadlines,
        // never the relative "EX 60" form.
        List<AofLog.Frame> frames = AofLog.read(file);
        boolean sawPexpireat = frames.stream().anyMatch(f -> "PEXPIREAT".equals(f.cmd()));
        assertTrue(sawPexpireat, "AOF should canonicalize to PEXPIREAT");
        boolean sawSetPxat = frames.stream()
                .anyMatch(f -> "SET".equals(f.cmd()) && f.args().contains("PXAT"));
        assertTrue(sawSetPxat, "SET with TTL should log absolute PXAT");

        // Rebuild by replaying the log through a fresh store.
        Store replayed = new Store();
        for (AofLog.Frame frame : frames) {
            AofReplay.apply(replayed, frame);
        }

        assertTrue(replayed.get("a").isEmpty(), "a should be deleted");
        assertEquals("2", replayed.get("b").orElse(null));
        assertEquals("2", replayed.get("counter").orElse(null));

        long ttlB = replayed.ttl("b");
        assertTrue(ttlB > 0 && ttlB <= 120, "ttl(b) in (0,120], got " + ttlB);
    }

    @Test
    void setWithTtlReplaysAbsoluteDeadline(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("appendonly.aof");
        Store store = new Store();
        AofLog aof = AofLog.open(file, false);
        store.setAof(aof);

        store.set("k", "v", Duration.ofSeconds(60));
        aof.close();

        // A single SET with PXAT must survive replay with its TTL intact,
        // with no later PEXPIREAT to mask a broken replay.
        List<AofLog.Frame> frames = AofLog.read(file);
        assertEquals(1, frames.size());

        Store replayed = new Store();
        for (AofLog.Frame frame : frames) {
            AofReplay.apply(replayed, frame);
        }

        assertEquals("v", replayed.get("k").orElse(null));
        long ttl = replayed.ttl("k");
        assertTrue(ttl > 0 && ttl <= 60, "ttl(k) in (0,60], got " + ttl);
    }

    @Test
    void delOfMissingKeyDoesNotEmitAOF(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("appendonly.aof");
        Store store = new Store();
        AofLog aof = AofLog.open(file, false);
        store.setAof(aof);

        boolean removed = store.del("never-was");
        assertFalse(removed);
        aof.close();

        // No-op del should not have written anything.
        assertEquals(0, AofLog.read(file).size());
    }
}
