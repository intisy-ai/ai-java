package io.github.intisy.ai.shared.store;

import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.Store;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelsCacheTest {

    // A models.json fixture in the exact shape produced by the JS
    // libs/core-auth/src/models-cache.ts writeModelCache — locks read() parity.
    private static final String FIXTURE = "{"
            + "\"claude-code\":{"
            + "\"models\":{"
            + "\"claude-3-opus\":{\"name\":\"Opus\",\"contextWindow\":200000},"
            + "\"claude-code-auto\":{\"name\":\"Auto\"}"
            + "},"
            + "\"ranking\":[\"claude-3-opus\"],"
            + "\"defaultModelId\":\"claude-3-opus\","
            + "\"source\":\"static\","
            + "\"sorts\":[{\"id\":\"leaderboard\",\"label\":\"Leaderboard\"}],"
            + "\"sortOrders\":{\"leaderboard\":[\"claude-3-opus\"]},"
            + "\"scores\":{\"claude-3-opus\":5},"
            + "\"scoreSource\":\"leaderboard\","
            + "\"fetchedAt\":1700000000000"
            + "}"
            + "}";

    @Test
    void read_parsesFixtureShapeAndFields() {
        Store store = new InMemoryStore();
        store.put("models.json", FIXTURE);

        ModelsCache cache = new ModelsCache(store, new TestJsonCodec());
        ModelsCache.Entry entry = cache.read("claude-code");

        assertTrue(entry != null);
        assertEquals(2, entry.models.size());
        assertTrue(entry.models.containsKey("claude-3-opus"));
        assertEquals(Arrays.asList("claude-3-opus"), entry.ranking);
        assertEquals("claude-3-opus", entry.defaultModelId);
        assertEquals("static", entry.source);
        assertEquals(1, entry.sorts.size());
        assertEquals(Arrays.asList("claude-3-opus"), entry.sortOrders.get("leaderboard"));
        assertEquals("leaderboard", entry.scoreSource);
        assertEquals(1700000000000L, entry.fetchedAt);
        // whole-number score must survive without gaining a trailing .0 (LONG_OR_DOUBLE parity)
        assertEquals(5L, ((Number) entry.scores.get("claude-3-opus")).longValue());
    }

    @Test
    void read_returnsEntryWhenModelsPresentEvenIfEmpty_nullWhenMissing() {
        Store store = new InMemoryStore();
        store.put("models.json", ""
                + "{"
                + "\"empty-models\":{\"models\":{}},"
                + "\"no-models-field\":{\"defaultModelId\":\"x\"}"
                + "}");

        ModelsCache cache = new ModelsCache(store, new TestJsonCodec());
        assertNull(cache.read("claude-code"));       // (a) provider missing entirely
        assertNull(cache.read("no-models-field"));   // (b) entry present but models is null/absent

        // (c) JS parity: entry.models is {} (truthy in JS) -> entry is returned, not null
        ModelsCache.Entry entry = cache.read("empty-models");
        assertTrue(entry != null);
        assertTrue(entry.models.isEmpty());
    }

    @Test
    void write_thenRead_roundTripsAndPreservesWholeNumbersWithoutTrailingZero() {
        Store store = new InMemoryStore();
        ModelsCache cache = new ModelsCache(store, new TestJsonCodec());

        ModelsCache.Entry entry = new ModelsCache.Entry();
        Map<String, Object> models = new LinkedHashMap<>();
        models.put("model-a", singleton("name", "Model A"));
        entry.models = models;
        entry.ranking = Arrays.asList("model-a");
        entry.defaultModelId = "model-a";
        entry.source = "live";
        entry.scores = singleton("model-a", 7); // Integer, not Double -> must not gain ".0"
        entry.fetchedAt = 1234L;

        cache.write("prov", entry);

        ModelsCache.Entry roundTripped = cache.read("prov");
        assertTrue(roundTripped != null);
        assertEquals("live", roundTripped.source);
        assertEquals(7L, ((Number) roundTripped.scores.get("model-a")).longValue());

        String raw = store.get("models.json");
        assertTrue(raw.contains("\"model-a\":7"));
        assertFalse(raw.contains("\"model-a\":7.0"));
    }

    @Test
    void write_defaultsFetchedAtToZeroWhenNull() {
        Store store = new InMemoryStore();
        ModelsCache cache = new ModelsCache(store, new TestJsonCodec());

        ModelsCache.Entry entry = new ModelsCache.Entry();
        entry.models = singleton("m", singleton("name", "M"));
        cache.write("prov", entry);

        ModelsCache.Entry roundTripped = cache.read("prov");
        assertEquals(0L, roundTripped.fetchedAt);
    }

    /**
     * Best-effort read resilience (JS/core parity): a corrupted {@code models.json} must degrade
     * to no cache rather than throwing out of {@code read}.
     */
    @Test
    void read_returnsNullWhenStoreContainsMalformedJson() {
        Store store = new InMemoryStore();
        store.put("models.json", "{ not json");
        ModelsCache cache = new ModelsCache(store, new TestJsonCodec());

        assertNull(cache.read("claude-code"));
    }

    /**
     * gson (no serializeNulls) parity: an entry with {@code models == null} must OMIT the
     * {@code models} field on write (not serialize it as {@code {}}), so a later read sees
     * {@code models == null} and returns null (no cache) rather than an empty-but-present entry.
     */
    @Test
    void write_omitsModelsFieldWhenNull_andReadReturnsNull() {
        Store store = new InMemoryStore();
        ModelsCache cache = new ModelsCache(store, new TestJsonCodec());

        ModelsCache.Entry entry = new ModelsCache.Entry();
        entry.defaultModelId = "x";
        entry.models = null;
        cache.write("prov", entry);

        String raw = store.get("models.json");
        assertFalse(raw.contains("\"models\":{}"));

        assertNull(cache.read("prov"));
    }

    private static Map<String, Object> singleton(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, value);
        return m;
    }
}
