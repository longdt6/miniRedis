package com.example.miniredis.web;

import com.example.miniredis.cmd.Reply;

import java.util.List;

public final class ReplyWriter {

    private ReplyWriter() {
    }

    public static ReplyDto toDto(Reply reply) {
        if (reply instanceof Reply.Simple s) {
            return new ReplyDto("simple", s.value(), false, null);
        } else if (reply instanceof Reply.Int i) {
            return new ReplyDto("int", String.valueOf(i.value()), false, null);
        } else if (reply instanceof Reply.Bulk b) {
            return new ReplyDto("bulk", b.isNil() ? null : b.value(), b.isNil(), null);
        } else if (reply instanceof Reply.Array a) {
            return new ReplyDto("array", null, false, a.values());
        } else if (reply instanceof Reply.Error e) {
            return new ReplyDto("error", e.message(), false, null);
        }
        throw new IllegalArgumentException("Unknown reply type: " + reply.getClass().getName());
    }

    public record ReplyDto(String type, String value, boolean nil, List<String> values) {
    }
}
