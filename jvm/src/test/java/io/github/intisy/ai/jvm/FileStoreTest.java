package io.github.intisy.ai.jvm;

import io.github.intisy.ai.jvm.backend.store.FileStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
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

class FileStoreTest {

    @Test
    void putThenGetRoundTrips(@TempDir Path dir) {
        FileStore store = new FileStore(dir.resolve("config"));

        store.put("accounts.json", "{\"version\":1}");

        assertEquals("{\"version\":1}", store.get("accounts.json"));
        assertTrue(store.exists("accounts.json"));
        assertTrue(Files.exists(dir.resolve("config").resolve("accounts.json")));
    }

    @Test
    void getOfMissingKeyReturnsNull(@TempDir Path dir) {
        FileStore store = new FileStore(dir.resolve("config"));
        assertNull(store.get("nope.json"));
        assertFalse(store.exists("nope.json"));
    }

    @Test
    void deleteRemovesTheFile(@TempDir Path dir) {
        FileStore store = new FileStore(dir.resolve("config"));
        store.put("accounts.json", "{}");
        assertTrue(store.exists("accounts.json"));

        store.delete("accounts.json");

        assertFalse(store.exists("accounts.json"));
    }

    @Test
    void updateIsAtomicReadModifyWrite(@TempDir Path dir) {
        FileStore store = new FileStore(dir.resolve("config"));
        store.put("counter.json", "{\"n\":0}");

        store.update("counter.json", current -> "{\"n\":1}");

        assertEquals("{\"n\":1}", store.get("counter.json"));
        // no leftover .tmp/.lock files after a clean update
        List<String> keys = store.listKeys("");
        assertEquals(List.of("counter.json"), keys);
    }

    @Test
    void updateOnMissingKeyStartsFromNull(@TempDir Path dir) {
        FileStore store = new FileStore(dir.resolve("config"));

        store.update("fresh.json", current -> {
            assertNull(current);
            return "{\"created\":true}";
        });

        assertEquals("{\"created\":true}", store.get("fresh.json"));
    }

    @Test
    void listKeysFiltersByPrefixAndExcludesLockAndTmpFiles(@TempDir Path dir) {
        FileStore store = new FileStore(dir.resolve("config"));
        store.put("accounts.json", "{}");
        store.put("models.json", "{}");

        List<String> keys = store.listKeys("acc");

        assertEquals(List.of("accounts.json"), keys);
    }

    @Test
    void concurrentUpdatesDoNotLoseWrites(@TempDir Path dir) throws InterruptedException {
        FileStore store = new FileStore(dir.resolve("config"));
        store.put("counter.json", "{\"n\":0}");

        int threads = 8;
        int incrementsPerThread = 50;
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
