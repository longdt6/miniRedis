package com.example.miniredis.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SnapshotCodec {

    public static final int CURRENT_VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SnapshotCodec() {
    }

    public static String encode(Map<String, Item> items) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", CURRENT_VERSION);
        root.put("items", items);
        return MAPPER.writeValueAsString(root);
    }

    public static Map<String, Item> decode(String json) throws IOException {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Map<String, Object> root = MAPPER.readValue(json, new TypeReference<>() {
        });
        Object itemsObj = root.get("items");
        if (!(itemsObj instanceof Map<?, ?> itemsMap)) {
            return Map.of();
        }
        Map<String, Item> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : itemsMap.entrySet()) {
            out.put(String.valueOf(e.getKey()), MAPPER.convertValue(e.getValue(), Item.class));
        }
        return out;
    }

    public static void atomicWrite(Path file, String json) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, json);
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
