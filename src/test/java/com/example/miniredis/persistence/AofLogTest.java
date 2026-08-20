package com.example.miniredis.persistence;

import com.example.miniredis.store.AofLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AofLogTest {

    @Test
    void appendAndReadRoundTrip(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("appendonly.aof");
        AofLog log = AofLog.open(file, false);
        log.append("SET", List.of("foo", "bar"));
        log.append("SET", List.of("baz", "qux", "PXAT", "1740000000000"));
        log.append("DEL", List.of("foo"));
        log.append("INCR", List.of("counter"));
        log.close();

        List<AofLog.Frame> frames = AofLog.read(file);
        assertEquals(4, frames.size());
        assertEquals("SET", frames.get(0).cmd());
        assertEquals(List.of("foo", "bar"), frames.get(0).args());
        assertEquals("1740000000000", frames.get(1).args().get(3));
        assertEquals("DEL", frames.get(2).cmd());
        assertEquals("INCR", frames.get(3).cmd());
    }

    @Test
    void readMissingFileReturnsEmpty(@TempDir Path tmp) throws Exception {
        assertTrue(AofLog.read(tmp.resolve("nope.aof")).isEmpty());
    }

    @Test
    void readStopsAtTruncatedTail(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("appendonly.aof");
        // One valid line, then a half-written line that won't parse.
        Files.writeString(file,
                "{\"cmd\":\"SET\",\"args\":[\"a\",\"1\"]}\n" +
                "{\"cmd\":\"DEL\",\"args\":[\"a\"");
        List<AofLog.Frame> frames = AofLog.read(file);
        assertEquals(1, frames.size(), "truncated last line must be dropped");
        assertEquals("SET", frames.get(0).cmd());
    }

    @Test
    void appendResumesExistingFile(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("appendonly.aof");
        AofLog first = AofLog.open(file, false);
        first.append("SET", List.of("a", "1"));
        first.close();

        AofLog second = AofLog.open(file, false);
        second.append("SET", List.of("b", "2"));
        second.close();

        List<AofLog.Frame> frames = AofLog.read(file);
        assertEquals(2, frames.size());
        assertEquals(List.of("a", "1"), frames.get(0).args());
        assertEquals(List.of("b", "2"), frames.get(1).args());
    }

    @Test
    void emptyArgsArePreserved(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("appendonly.aof");
        AofLog log = AofLog.open(file, false);
        log.append("FLUSHALL", List.of());
        log.close();

        List<AofLog.Frame> frames = AofLog.read(file);
        assertEquals(1, frames.size());
        assertEquals("FLUSHALL", frames.get(0).cmd());
        assertTrue(frames.get(0).args().isEmpty());
    }
}
