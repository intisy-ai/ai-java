package io.github.intisy.ai.jvm;

import io.github.intisy.ai.jvm.backend.store.InMemoryStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryStoreTest {

    @Test
    void putThenGetRoundTrips() {
        InMemoryStore store = new InMemoryStore();

        store.put("accounts.json", "{\"version\":1}");

        assertEquals("{\"version\":1}", store.get("accounts.json"));
        assertTrue(store.exists("accounts.json"));
    }

    @Test
    void getOfMissingKeyReturnsNull() {
        InMemoryStore store = new InMemoryStore();
        assertNull(store.get("nope.json"));
        assertFalse(store.exists("nope.json"));
    }

    @Test
    void deleteRemovesTheKey() {
        InMemoryStore store = new InMemoryStore();
        store.put("accounts.json", "{}");
        assertTrue(store.exists("accounts.json"));

        store.delete("accounts.json");

        assertFalse(store.exists("accounts.json"));
        assertNull(store.get("accounts.json"));
    }

    @Test
    void deleteOfMissingKeyIsANoOp() {
        InMemoryStore store = new InMemoryStore();
        store.delete("nope.json");
        assertFalse(store.exists("nope.json"));
    }

    @Test
    void updateIsReadModifyWrite() {
        InMemoryStore store = new InMemoryStore();
        store.put("counter.json", "{\"n\":0}");

        store.update("counter.json", current -> "{\"n\":1}");

        assertEquals("{\"n\":1}", store.get("counter.json"));
    }

    @Test
    void updateOnMissingKeyStartsFromNull() {
        InMemoryStore store = new InMemoryStore();

        store.update("fresh.json", current -> {
            assertNull(current);
            return "{\"created\":true}";
        });

        assertEquals("{\"created\":true}", store.get("fresh.json"));
    }

    @Test
    void listKeysFiltersByPrefix() {
        InMemoryStore store = new InMemoryStore();
        store.put("accounts.json", "{}");
        store.put("models.json", "{}");

        List<String> keys = store.listKeys("acc");

        assertEquals(List.of("accounts.json"), keys);
    }

    @Test
    void concurrentUpdatesDoNotLoseWrites() throws InterruptedException {
        InMemoryStore store = new InMemoryStore();
        store.put("counter.json", "{\"n\":0}");

        int threads = 16;
        int incrementsPerThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    for (int i = 0; i < incrementsPerThread; i++) {
                        store.update("counter.json", current -> {
                            int n = Integer.parseInt(current.replaceAll("[^0-9]", ""));
                            return "{\"n\":" + (n + 1) + "}";
                        });
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(30, TimeUnit.SECONDS), "updates did not finish in time");
        pool.shutdown();

        assertEquals(0, errors.get());
        String finalValue = store.get("counter.json");
        int n = Integer.parseInt(finalValue.replaceAll("[^0-9]", ""));
        assertEquals(threads * incrementsPerThread, n, "lost update: " + finalValue);
    }
}
