package com.example.miniredis.web;

import com.example.miniredis.cmd.Reply;

import java.util.List;

public final class ReplyWriter {

    private ReplyWriter() {
    }

    public static ReplyDto toDto(Reply reply) {
        return switch (reply) {
            case Reply.Simple s -> new ReplyDto("simple", s.value(), false, null);
            case Reply.Int i -> new ReplyDto("int", String.valueOf(i.value()), false, null);
            case Reply.Bulk b -> new ReplyDto("bulk", b.isNil() ? null : b.value(), b.isNil(), null);
            case Reply.Array a -> new ReplyDto("array", null, false, a.values());
            case Reply.Error e -> new ReplyDto("error", e.message(), false, null);
        };
    }

    public record ReplyDto(String type, String value, boolean nil, List<String> values) {
    }
}
