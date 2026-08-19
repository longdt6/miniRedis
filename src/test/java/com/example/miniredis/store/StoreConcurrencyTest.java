package com.example.miniredis.store;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

class StoreConcurrencyTest {

    @Test
    void incrUnderContentionIsExact() throws Exception {
        Store store = new Store();
        try {
            int threads = 32;
            int incrementsPerThread = 10_000;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < incrementsPerThread; i++) {
                            store.incr("counter");
                        }
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            if (!done.await(60, TimeUnit.SECONDS)) {
                fail("workers did not finish in time");
            }
            pool.shutdownNow();

            if (failure.get() != null) {
                throw new AssertionError("worker failed", failure.get());
            }
            assertEquals((long) threads * incrementsPerThread, store.size() == 0 ? 0L : Long.parseLong(store.get("counter").orElse("0")));
        } finally {
            // ensure daemon thread doesn't linger between tests
            try {
                var stop = Store.class.getDeclaredMethod("stop");
                stop.setAccessible(true);
                stop.invoke(store);
            } catch (Exception ignored) {
            }
        }
    }
}