package io.github.intisy.ai.core.store;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelsCacheTest {
    // A models.json fixture written in the exact shape produced by the JS
    // libs/core-auth/src/models-cache.ts writeModelCache — used to lock read() parity.
    private static final String FIXTURE = "{\n"
            + "  \"claude-code\": {\n"
            + "    \"models\": {\n"
            + "      \"claude-3-opus\": { \"name\": \"Opus\", \"contextWindow\": 200000 },\n"
            + "      \"claude-code-auto\": { \"name\": \"Auto\" }\n"
            + "    },\n"
            + "    \"ranking\": [\"claude-3-opus\"],\n"
            + "    \"defaultModelId\": \"claude-3-opus\",\n"
            + "    \"source\": \"static\",\n"
            + "    \"sorts\": [ { \"id\": \"leaderboard\", \"label\": \"Leaderboard\" } ],\n"
            + "    \"sortOrders\": { \"leaderboard\": [\"claude-3-opus\"] },\n"
            + "    \"scores\": { \"claude-3-opus\": 5 },\n"
            + "    \"scoreSource\": \"leaderboard\",\n"
            + "    \"fetchedAt\": 1700000000000\n"
            + "  }\n"
            + "}\n";

    @Test
    void read_parsesJsFixtureShapeAndFields() throws Exception {
        Path cf = Files.createTempDirectory("ai-models");
        Files.write(cf.resolve("models.json"), FIXTURE.getBytes());

        ModelsCache cache = new ModelsCache(cf);
        ModelsCache.Entry entry = cache.read("claude-code");

        assertTrue(entry != null);
        assertEquals(2, entry.models.size());
        assertTrue(entry.models.containsKey("claude-3-opus"));
        assertEquals(List.of("claude-3-opus"), entry.ranking);
        assertEquals("claude-3-opus", entry.defaultModelId);
        assertEquals("static", entry.source);
        assertEquals(1, entry.sorts.size());
        assertEquals(List.of("claude-3-opus"), entry.sortOrders.get("leaderboard"));
        assertEquals("leaderboard", entry.scoreSource);
        assertEquals(1700000000000L, entry.fetchedAt);
        // whole-number score must survive without gaining a trailing .0 (LONG_OR_DOUBLE parity)
        assertEquals(5L, ((Number) entry.scores.get("claude-3-opus")).longValue());
    }

    @Test
    void read_returnsNullWhenProviderMissingOrHasNoModels() throws Exception {
        Path cf = Files.createTempDirectory("ai-models-empty");
        Files.write(cf.resolve("models.json"), "{ \"other\": { \"models\": {} } }".getBytes());

        ModelsCache cache = new ModelsCache(cf);
        assertNull(cache.read("claude-code")); // absent entirely
        assertNull(cache.read("other"));       // present but empty models map
    }

    @Test
    void write_thenRead_roundTripsAndPreservesWholeNumbersWithoutTrailingZero() throws Exception {
        Path cf = Files.createTempDirectory("ai-models-write");
        ModelsCache cache = new ModelsCache(cf);

        ModelsCache.Entry entry = new ModelsCache.Entry();
        entry.models = Map.of("model-a", Map.of("name", "Model A"));
        entry.ranking = List.of("model-a");
        entry.defaultModelId = "model-a";
        entry.source = "live";
        entry.scores = Map.of("model-a", 7);
        entry.fetchedAt = 1234L;

        cache.write("prov", entry);

        ModelsCache.Entry roundTripped = cache.read("prov");
        assertTrue(roundTripped != null);
        assertEquals("live", roundTripped.source);
        assertEquals(7L, ((Number) roundTripped.scores.get("model-a")).longValue());

        String json = Files.readString(cf.resolve("models.json"));
        assertTrue(json.contains("\"model-a\": 7"));
        assertFalse(json.contains("\"model-a\": 7.0"));
    }

    @Test
    void write_dropsLegacyFileOnceRenamedFileExists() throws Exception {
        Path cf = Files.createTempDirectory("ai-models-legacy");
        Files.write(cf.resolve("core-auth-models.json"), "{}".getBytes());

        ModelsCache cache = new ModelsCache(cf);
        ModelsCache.Entry entry = new ModelsCache.Entry();
        entry.models = Map.of("m", Map.of("name", "M"));
        cache.write("prov", entry);

        assertFalse(Files.exists(cf.resolve("core-auth-models.json")));
        assertTrue(Files.exists(cf.resolve("models.json")));
    }
}
