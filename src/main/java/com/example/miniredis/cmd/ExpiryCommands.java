package com.example.miniredis.cmd;

import com.example.miniredis.store.Store;

import java.util.List;

public final class ExpiryCommands {

    private ExpiryCommands() {
    }

    public static Reply expire(Store s, List<String> args) {
        if (args.size() < 2) {
            return new Reply.Error("ERR wrong number of arguments for 'expire' command");
        }
        String key = args.get(0);
        long seconds = Long.parseLong(args.get(1));
        return new Reply.Int(s.setExpiry(key, seconds) ? 1L : 0L);
    }

    public static Reply ttl(Store s, List<String> args) {
        if (args.isEmpty()) {
            return new Reply.Error("ERR wrong number of arguments for 'ttl' command");
        }
        return new Reply.Int(s.ttl(args.get(0)));
    }

    public static Reply pexpireat(Store s, List<String> args) {
        if (args.size() < 2) {
            return new Reply.Error("ERR wrong number of arguments for 'pexpireat' command");
        }
        long epochMs;
        try {
            epochMs = Long.parseLong(args.get(1));
        } catch (NumberFormatException e) {
            return new Reply.Error("ERR value is not a valid integer");
        }
        return new Reply.Int(s.setExpiryAt(args.get(0), epochMs) ? 1L : 0L);
    }
}
