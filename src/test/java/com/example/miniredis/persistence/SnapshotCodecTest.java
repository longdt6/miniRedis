package com.example.miniredis.persistence;

import com.example.miniredis.store.Item;
import com.example.miniredis.store.SnapshotCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotCodecTest {

    @Test
    void roundTrip() throws Exception {
        Map<String, Item> input = new LinkedHashMap<>();
        input.put("a", Item.withoutExpiry("alpha"));
        input.put("b", Item.withExpiry("beta", System.currentTimeMillis() + 60_000));
        String json = SnapshotCodec.encode(input);
        Map<String, Item> out = SnapshotCodec.decode(json);
        assertEquals(2, out.size());
        assertEquals("alpha", out.get("a").value());
        assertEquals("beta", out.get("b").value());
        assertTrue(out.get("b").hasExpiry());
        assertFalse(out.get("a").hasExpiry());
    }

    @Test
    void atomicWriteNoTmpLeft(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("snap.json");
        SnapshotCodec.atomicWrite(file, "{\"version\":1,\"items\":{}}");
        assertTrue(Files.exists(file));
        try (var stream = Files.list(tmp)) {
            long tmpFiles = stream.filter(p -> p.getFileName().toString().endsWith(".tmp")).count();
            assertEquals(0, tmpFiles, "tmp file should not remain after atomic move");
        }
    }

    @Test
    void atomicWriteOverExisting(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("snap.json");
        SnapshotCodec.atomicWrite(file, "{\"v\":1}");
        SnapshotCodec.atomicWrite(file, "{\"v\":2}");
        assertEquals("{\"v\":2}", Files.readString(file));
    }

    @Test
    void loadSkipsExpiredLogic() throws Exception {
        Map<String, Item> items = new LinkedHashMap<>();
        items.put("live", Item.withExpiry("yes", System.currentTimeMillis() + 60_000));
        items.put("dead", Item.withExpiry("no", System.currentTimeMillis() - 1000));
        String json = SnapshotCodec.encode(items);
        Map<String, Item> decoded = SnapshotCodec.decode(json);
        assertEquals(2, decoded.size());
        assertTrue(decoded.containsKey("live"));
        assertTrue(decoded.containsKey("dead"));
    }

    @Test
    void decodeEmpty() throws Exception {
        assertTrue(SnapshotCodec.decode("").isEmpty());
        assertTrue(SnapshotCodec.decode(null).isEmpty());
    }
}
