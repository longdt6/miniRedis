package com.example.miniredis.store;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Item(String value, boolean hasExpiry, Long expiresAtEpochMs) {
    public static Item withoutExpiry(String value) {
        return new Item(value, false, null);
    }

    public static Item withExpiry(String value, long expiresAtEpochMs) {
        return new Item(value, true, expiresAtEpochMs);
    }
}
