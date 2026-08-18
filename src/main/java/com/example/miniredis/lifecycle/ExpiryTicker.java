package com.example.miniredis.lifecycle;

import com.example.miniredis.store.Store;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class ExpiryTicker {

    @Inject
    Store store;

    @ConfigProperty(name = "miniredis.expiry-sweep-ms", defaultValue = "1000")
    long sweepMs;

    private ScheduledExecutorService scheduler;

    void onStart(@Observes StartupEvent ev) {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "miniredis-expiry");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(store::removeExpired, sweepMs, sweepMs, TimeUnit.MILLISECONDS);
    }

    void onStop(@Observes ShutdownEvent ev) {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
