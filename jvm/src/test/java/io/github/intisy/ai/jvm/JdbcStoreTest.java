package io.github.intisy.ai.jvm;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcStoreTest {

    // a fresh, uniquely-named H2 in-memory DB per call so tests never see each other's rows;
    // DB_CLOSE_DELAY=-1 keeps it alive for the JVM's life instead of dropping it the instant
    // the first connection closes. LOCK_TIMEOUT is raised so the concurrency test's FOR UPDATE
    // row lock waits don't spuriously time out under heavy thread contention.
    private static DataSource newH2DataSource() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:jdbcstore-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=30000");
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }

    @Test
    void createsTableOnConstructionAndRoundTripsGetPut() {
        JdbcStore store = new JdbcStore(newH2DataSource());

        store.put("accounts.json", "{\"version\":1}");

        assertEquals("{\"version\":1}", store.get("accounts.json"));
        assertTrue(store.exists("accounts.json"));
    }

    @Test
    void getOfMissingKeyReturnsNull() {
        JdbcStore store = new JdbcStore(newH2DataSource());
        assertNull(store.get("nope.json"));
        assertFalse(store.exists("nope.json"));
    }

    @Test
    void putUpsertsOverwritingAnExistingValue() {
        JdbcStore store = new JdbcStore(newH2DataSource());
        store.put("accounts.json", "{\"version\":1}");

        store.put("accounts.json", "{\"version\":2}");

        assertEquals("{\"version\":2}", store.get("accounts.json"));
    }

    @Test
    void deleteRemovesTheRow() {
        JdbcStore store = new JdbcStore(newH2DataSource());
        store.put("accounts.json", "{}");
        assertTrue(store.exists("accounts.json"));

        store.delete("accounts.json");

        assertFalse(store.exists("accounts.json"));
        assertNull(store.get("accounts.json"));
    }

    @Test
    void deleteOfMissingKeyIsANoOp() {
        JdbcStore store = new JdbcStore(newH2DataSource());
        store.delete("nope.json");
        assertFalse(store.exists("nope.json"));
    }

    @Test
    void updateIsAtomicReadModifyWrite() {
        JdbcStore store = new JdbcStore(newH2DataSource());
        store.put("counter.json", "{\"n\":0}");

        store.update("counter.json", current -> "{\"n\":1}");

        assertEquals("{\"n\":1}", store.get("counter.json"));
    }

    @Test
    void updateOnMissingKeyStartsFromNull() {
        JdbcStore store = new JdbcStore(newH2DataSource());

        store.update("fresh.json", current -> {
            assertNull(current);
            return "{\"created\":true}";
        });

        assertEquals("{\"created\":true}", store.get("fresh.json"));
    }

    @Test
    void listKeysFiltersByPrefix() {
        JdbcStore store = new JdbcStore(newH2DataSource());
        store.put("accounts.json", "{}");
        store.put("models.json", "{}");

        List<String> keys = store.listKeys("acc");

        assertEquals(List.of("accounts.json"), keys);
    }

    @Test
    void customTableNameIsUsedInsteadOfTheDefault() {
        DataSource ds = newH2DataSource();
        JdbcStore custom = new JdbcStore(ds, "custom_kv");

        custom.put("accounts.json", "{}");
        assertTrue(custom.exists("accounts.json"));

        // a second store over the same DataSource but the default table name sees nothing:
        // proves the custom table name was actually used, not just accepted and ignored.
        JdbcStore defaultTable = new JdbcStore(ds);
        assertFalse(defaultTable.exists("accounts.json"));
    }

    @Test
    void concurrentUpdatesOnTheSameKeyDoNotLoseWrites() throws InterruptedException {
        JdbcStore store = new JdbcStore(newH2DataSource());
        store.put("counter.json", "{\"n\":0}");

        int threads = 16;
        int incrementsPerThread = 20;
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
        assertTrue(done.await(60, TimeUnit.SECONDS), "updates did not finish in time");
        pool.shutdown();

        assertEquals(0, errors.get());
        String finalValue = store.get("counter.json");
        int n = Integer.parseInt(finalValue.replaceAll("[^0-9]", ""));
        assertEquals(threads * incrementsPerThread, n, "lost update: " + finalValue);
    }
}
