package com.example.miniredis.store;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Replays a single {@link AofLog.Frame} onto a {@link Store}.
 *
 * <p>The AOF only ever logs mutating commands, so this switch covers exactly
 * the set {@link Store} emits (SET, DEL, INCR, PEXPIREAT, FLUSHALL). Replaying
 * goes through this small switch rather than the public {@code CommandDispatcher}
 * so the on-disk format stays independent of the command parser.
 *
 * <p>Replay must be run before {@link Store#setAof(AofLog)} is installed, so
 * the mutations it issues are not appended back to the log.
 */
public final class AofReplay {

    private AofReplay() {
    }

    public static void apply(Store store, AofLog.Frame frame) {
        List<String> args = frame.args();
        switch (frame.cmd()) {
            case "SET" -> {
                String key = args.get(0);
                String value = args.get(1);
                // A SET with a TTL is canonicalized to "SET key value PXAT <ms>".
                if (args.size() >= 4 && "PXAT".equalsIgnoreCase(args.get(2))) {
                    long deadline = Long.parseLong(args.get(3));
                    long remaining = deadline - Instant.now().toEpochMilli();
                    if (remaining > 0) {
                        store.set(key, value, Duration.ofMillis(remaining));
                    }
                    // else: the deadline has already passed — leave the key unset.
                } else {
                    store.set(key, value, null);
                }
            }
            case "DEL" -> {
                for (String k : args) {
                    store.del(k);
                }
            }
            case "INCR" -> store.incr(args.get(0));
            case "PEXPIREAT" -> store.setExpiryAt(args.get(0), Long.parseLong(args.get(1)));
            case "FLUSHALL" -> store.flushAll();
            default -> throw new IllegalArgumentException("Unknown AOF command: " + frame.cmd());
        }
    }
}
