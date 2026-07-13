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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    // Regression test for the lost-update bug: SELECT ... FOR UPDATE locks nothing when the
    // key's row doesn't exist yet, so two concurrent update() calls on an absent key both used
    // to read null, both compute from null, and one's UPDATE silently clobbered the other's
    // already-committed INSERT with no exception. This key is NEVER pre-put - it starts (and,
    // for the whole first round of racing updates, stays) absent, which is exactly the gap that
    // ensure-row-then-lock closes: update() now inserts a NULL-v placeholder row before the
    // FOR UPDATE select, so there is always a real row for every concurrent update() to lock
    // and serialize on, even the very first one on a never-written key.
    @Test
    void concurrentUpdatesOnAnAbsentKeyDoNotLoseWrites() throws InterruptedException {
        JdbcStore store = new JdbcStore(newH2DataSource());

        int threads = 8;
        int incrementsPerThread = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    for (int i = 0; i < incrementsPerThread; i++) {
                        store.update("absent-counter.json", current -> {
                            // current is null both on the very first increment (key never
                            // put) and never again after - proves NULL v is read back as
                            // "absent" rather than as a real empty/zero value.
                            int n = current == null
                                    ? 0
                                    : Integer.parseInt(current.replaceAll("[^0-9]", ""));
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
        String finalValue = store.get("absent-counter.json");
        assertNotNull(finalValue, "key ended up with no value at all");
        int n = Integer.parseInt(finalValue.replaceAll("[^0-9]", ""));
        assertEquals(threads * incrementsPerThread, n, "lost update on absent key: " + finalValue);
    }

    // put() used to run its own UPDATE-then-INSERT upsert outside update()'s locking, so a
    // race between two put()s on the SAME never-written key could throw a spurious primary-key
    // violation (both miss the UPDATE, both attempt the INSERT). put() now delegates to
    // update()'s ensure-row-then-lock path, which serializes these instead of racing them.
    @Test
    void concurrentPutsOnAnAbsentKeyDoNotThrow() throws InterruptedException {
        JdbcStore store = new JdbcStore(newH2DataSource());

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            int writer = t;
            pool.submit(() -> {
                try {
                    ready.countDown();
                    go.await();
                    store.put("fresh-put.json", "{\"writer\":" + writer + "}");
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS), "workers did not all start in time");
        go.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "puts did not finish in time");
        pool.shutdown();

        assertEquals(0, errors.get());
        assertTrue(store.exists("fresh-put.json"));
        assertNotNull(store.get("fresh-put.json"));
    }

    // Any throwable from the mutator - not just SQLException - must roll back the WHOLE
    // transaction, including the ensure-row-then-lock placeholder INSERT. Otherwise a failed
    // update() on a previously-absent key would leave behind a row with v = NULL: exists()
    // must still report false and get() must still report null, i.e. the key must look exactly
    // as absent as it did before the failed call, with no leaked transaction on the pooled
    // connection either.
    @Test
    void mutatorExceptionRollsBackLeavingKeyFullyAbsent() {
        JdbcStore store = new JdbcStore(newH2DataSource());

        assertThrows(RuntimeException.class, () -> store.update("boom.json", current -> {
            throw new IllegalStateException("mutator failure");
        }));

        assertFalse(store.exists("boom.json"));
        assertNull(store.get("boom.json"));
    }
}
