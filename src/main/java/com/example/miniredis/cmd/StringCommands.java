package com.example.miniredis.cmd;

import com.example.miniredis.store.Store;

import java.util.List;

public final class StringCommands {

    private StringCommands() {
    }

    public static Reply ping(Store s, List<String> args) {
        if (args.isEmpty()) {
            return new Reply.Simple("PONG");
        }
        return new Reply.Simple(args.get(0));
    }

    public static Reply set(Store s, List<String> args) {
        if (args.size() < 2) {
            return new Reply.Error("ERR wrong number of arguments for 'set' command");
        }
        String key = args.get(0);
        String value = args.get(1);
        java.time.Duration ttl = null;
        for (int i = 2; i < args.size() - 1; i++) {
            String opt = args.get(i).toUpperCase();
            if ("EX".equals(opt)) {
                long seconds = Long.parseLong(args.get(i + 1));
                ttl = java.time.Duration.ofSeconds(seconds);
                i++;
            } else if ("PX".equals(opt)) {
                long ms = Long.parseLong(args.get(i + 1));
                ttl = java.time.Duration.ofMillis(ms);
                i++;
            }
        }
        s.set(key, value, ttl);
        return new Reply.Simple("OK");
    }

    public static Reply get(Store s, List<String> args) {
        if (args.isEmpty()) {
            return new Reply.Error("ERR wrong number of arguments for 'get' command");
        }
        return s.get(args.get(0))
                .map(v -> (Reply) new Reply.Bulk(v, false))
                .orElse(new Reply.Bulk(null, true));
    }

    public static Reply del(Store s, List<String> args) {
        long count = 0L;
        for (String k : args) {
            if (s.del(k)) {
                count++;
            }
        }
        return new Reply.Int(count);
    }

    public static Reply exists(Store s, List<String> args) {
        long count = 0L;
        for (String k : args) {
            count += s.exists(k);
        }
        return new Reply.Int(count);
    }

    public static Reply incr(Store s, List<String> args) {
        if (args.isEmpty()) {
            return new Reply.Error("ERR wrong number of arguments for 'incr' command");
        }
        try {
            long v = s.incr(args.get(0));
            return new Reply.Int(v);
        } catch (NumberFormatException e) {
            return new Reply.Error("ERR value is not an integer or out of range");
        }
    }

    public static Reply keys(Store s, List<String> args) {
        return new Reply.Array(s.keys());
    }

    public static Reply flushAll(Store s, List<String> args) {
        s.flushAll();
        return new Reply.Simple("OK");
    }
}
