package com.example.miniredis.store;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The append-only file (AOF): a durable log of every mutating command, written
 * one JSON line per command and replayed in order at boot.
 *
 * <p>Unlike {@link SnapshotCodec} (which dumps the final map), AOF records the
 * <em>sequence of operations</em>. A snapshot answers "what is the state now?";
 * AOF answers "how did we get here?". That difference is why AOF can be appended
 * the instant a command finishes — no stop-the-world, no fork.
 *
 * <p>Three things to notice, each a real Redis mechanism:
 * <ul>
 *   <li><b>Canonicalization</b> — a relative {@code EXPIRE key 100} is never logged
 *       verbatim. {@link Store} logs the absolute deadline as {@code PEXPIREAT key
 *       <epoch-ms>}, so replaying after a restart still expires at the right instant.</li>
 *   <li><b>fsync policy</b> — {@code fsyncOnWrite=true} is {@code appendfsync always}
 *       (fsync every command, zero loss, slow); {@code false} is {@code appendfsync
 *       everysec/no} (the OS decides when to flush, so a crash can lose the tail).</li>
 *   <li><b>Truncated tail</b> — {@link #read} stops at the first line that fails to
 *       parse, dropping a half-written final command, like Redis' {@code aof-load-truncated}.</li>
 * </ul>
 *
 * <p>Appends are serialized by {@link Store}'s single worker thread, so callers
 * must not append concurrently.
 */
public final class AofLog implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A single command frame: the command name and its (canonicalized) arguments. */
    public record Frame(String cmd, List<String> args) {
    }

    private final FileOutputStream out;
    private final boolean fsyncOnWrite;

    private AofLog(FileOutputStream out, boolean fsyncOnWrite) {
        this.out = out;
        this.fsyncOnWrite = fsyncOnWrite;
    }

    /**
     * Opens (or creates) {@code file} in append mode. {@code fsyncOnWrite} selects
     * the {@code appendfsync always} vs. {@code everysec} policy.
     */
    public static AofLog open(Path file, boolean fsyncOnWrite) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        FileOutputStream out = new FileOutputStream(file.toFile(), true);
        return new AofLog(out, fsyncOnWrite);
    }

    /** Appends one command frame as a single JSON line, fsyncing if configured. */
    public void append(String cmd, List<String> args) throws IOException {
        String line = MAPPER.writeValueAsString(new Frame(cmd, args));
        out.write((line + '\n').getBytes(StandardCharsets.UTF_8));
        if (fsyncOnWrite) {
            out.getFD().sync();
        }
    }

    @Override
    public void close() throws IOException {
        out.close();
    }

    /**
     * Reads the AOF into frames, stopping at the first line that does not parse
     * (a truncated tail). A missing file yields an empty log.
     */
    public static List<Frame> read(Path file) throws IOException {
        if (!Files.exists(file)) {
            return List.of();
        }
        List<Frame> frames = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            try {
                frames.add(MAPPER.readValue(line, Frame.class));
            } catch (IOException e) {
                // Truncated tail: keep everything before it, drop the partial line.
                break;
            }
        }
        return frames;
    }
}
