package com.example.miniredis.lifecycle;

import com.example.miniredis.cmd.CommandDispatcher;
import com.example.miniredis.store.AofLog;
import com.example.miniredis.store.AofReplay;
import com.example.miniredis.store.SnapshotCodec;
import com.example.miniredis.store.Store;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Two-tier persistence: a JSON snapshot (the whole state) plus an append-only
 * file (commands since the last snapshot).
 *
 * <p>Boot order is significant: load snapshot first, then replay the AOF on top,
 * then open the AOF for appending. Closing the AOF before writing the snapshot,
 * and only deleting the AOF after a successful snapshot, keeps the pair
 * consistent under both clean shutdown and crash.
 *
 * <p>On clean shutdown: snapshot is written, AOF is deleted (snapshot now holds
 * everything). On crash: snapshot is stale, AOF has the tail; replay rebuilds.
 */
@ApplicationScoped
public class PersistenceLifecycle {

    private static final Logger LOG = Logger.getLogger(PersistenceLifecycle.class);

    @Inject
    Store store;

    @Inject
    CommandDispatcher dispatcher;

    @ConfigProperty(name = "miniredis.data-file")
    String dataFile;

    @ConfigProperty(name = "miniredis.aof-file")
    String aofFile;

    @ConfigProperty(name = "miniredis.aof-fsync", defaultValue = "everysec")
    String aofFsync;

    @ConfigProperty(name = "miniredis.aof-enabled", defaultValue = "true")
    boolean aofEnabled;

    private AofLog aof;

    void onStart(@Observes StartupEvent ev) {
        loadSnapshot();
        if (aofEnabled) {
            replayAof();
            try {
                aof = AofLog.open(Path.of(aofFile), "always".equalsIgnoreCase(aofFsync));
                store.setAof(aof);
            } catch (IOException e) {
                LOG.warnf(e, "Failed to open AOF at %s — running without AOF", aofFile);
                aof = null;
                store.setAof(null);
            }
            LOG.infof("AOF enabled at %s (fsync=%s)", aofFile, aofFsync);
        } else {
            LOG.info("AOF disabled (miniredis.aof-enabled=false)");
        }
    }

    void onStop(@Observes ShutdownEvent ev) {
        closeAof();
        boolean snapshotted = writeSnapshot();
        if (snapshotted && aofEnabled) {
            // The snapshot now holds the whole dataset; the AOF tail is redundant.
            resetAof();
        }
    }

    private void loadSnapshot() {
        Path file = Path.of(dataFile);
        if (!Files.exists(file)) {
            LOG.infof("No snapshot at %s, starting empty", file);
            return;
        }
        try {
            String json = Files.readString(file);
            var items = SnapshotCodec.decode(json);
            store.load(items);
            LOG.infof("Loaded %d keys from %s", items.size(), file);
        } catch (IOException e) {
            LOG.warnf(e, "Failed to load snapshot at %s", file);
        }
    }

    private void replayAof() {
        Path file = Path.of(aofFile);
        if (!Files.exists(file)) {
            return;
        }
        try {
            List<AofLog.Frame> frames = AofLog.read(file);
            for (AofLog.Frame frame : frames) {
                applyFrame(frame);
            }
            LOG.infof("Replayed %d AOF commands from %s", frames.size(), file);
        } catch (IOException e) {
            LOG.warnf(e, "Failed to replay AOF at %s", file);
        }
    }

    /**
     * Replays one AOF frame onto the store. Mutating only — AOF never logs read
     * commands, so the dispatcher is bypassed for the simple command set we
     * emit (SET, DEL, INCR, PEXPIREAT, FLUSHALL). Going through the dispatcher
     * would also work, but {@link AofReplay} keeps the on-disk format and the
     * public command parser independent.
     */
    private void applyFrame(AofLog.Frame frame) {
        try {
            AofReplay.apply(store, frame);
        } catch (RuntimeException e) {
            // A poison frame should not abort the whole replay: log and continue.
            LOG.warnf(e, "Failed to apply AOF frame %s %s", frame.cmd(), frame.args());
        }
    }

    private void closeAof() {
        if (aof != null) {
            store.setAof(null);
            try {
                aof.close();
            } catch (IOException e) {
                LOG.warnf(e, "Failed to close AOF");
            }
            aof = null;
        }
    }

    private boolean writeSnapshot() {
        Path file = Path.of(dataFile);
        try {
            String json = SnapshotCodec.encode(store.snapshot());
            SnapshotCodec.atomicWrite(file, json);
            LOG.infof("Snapshot written to %s", file);
            return true;
        } catch (IOException e) {
            LOG.warnf(e, "Failed to write snapshot at %s", file);
            return false;
        }
    }

    private void resetAof() {
        try {
            Files.deleteIfExists(Path.of(aofFile));
        } catch (IOException e) {
            LOG.warnf(e, "Failed to reset AOF at %s", aofFile);
        }
    }
}
