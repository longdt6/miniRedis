package com.example.miniredis.cmd;

import java.util.List;

public sealed interface Reply
        permits Reply.Simple, Reply.Int, Reply.Bulk, Reply.Array, Reply.Error {

    record Simple(String value) implements Reply {
    }

    record Int(long value) implements Reply {
    }

    record Bulk(String value, boolean isNil) implements Reply {
    }

    record Array(List<String> values) implements Reply {
    }

    record Error(String message) implements Reply {
    }
}
