package com.example.miniredis.lifecycle;

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

@ApplicationScoped
public class SnapshotOnShutdown {

    private static final Logger LOG = Logger.getLogger(SnapshotOnShutdown.class);

    @Inject
    Store store;

    @ConfigProperty(name = "miniredis.data-file")
    String dataFile;

    void onStart(@Observes StartupEvent ev) {
        Path file = Path.of(dataFile);
        if (!Files.exists(file)) {
            LOG.infof("No snapshot at %s, starting empty", file);
            return;
        }
        try {
            String json = Files.readString(file);
            var items = com.example.miniredis.store.SnapshotCodec.decode(json);
            store.load(items);
            LOG.infof("Loaded %d keys from %s", items.size(), file);
        } catch (IOException e) {
            LOG.warnf(e, "Failed to load snapshot at %s", file);
        }
    }

    void onStop(@Observes ShutdownEvent ev) {
        Path file = Path.of(dataFile);
        try {
            String json = com.example.miniredis.store.SnapshotCodec.encode(store.snapshot());
            com.example.miniredis.store.SnapshotCodec.atomicWrite(file, json);
            LOG.infof("Snapshot written to %s", file);
        } catch (IOException e) {
            LOG.warnf(e, "Failed to write snapshot at %s", file);
        }
    }
}
