package io.github.intisy.ai.shared.logic;

import io.github.intisy.ai.shared.routing.Assignment;
import io.github.intisy.ai.shared.routing.CatalogEntry;
import io.github.intisy.ai.shared.routing.RoutingProfile;
import io.github.intisy.ai.shared.store.InMemoryStore;
import io.github.intisy.ai.shared.store.TestJsonCodec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ported from {@code proxy/.../ModelMapTest} (Task 6): same scenarios, rewired onto the
 * {@link InMemoryStore} + {@link TestJsonCodec} SPI fakes instead of a temp-dir filesystem.
 */
class ModelMapTest {

    // TEST DATA ONLY (per task-3-brief.md) — not engine logic. A real profile (e.g.
    // Claude's) supplies its own configFile/tierSourceProvider/tierRegex/tierOrder.
    private static RoutingProfile testProfile() {
        RoutingProfile p = new RoutingProfile();
        p.configFile = "model-map-test.json";
        p.routingKey = "providerRouting";
        p.tierSourceProvider = "antigravity";
        p.tierOrder = List.of("opus", "sonnet", "haiku", "fable");
        p.tierFallback = List.of("opus", "sonnet", "haiku", "fable");
        p.tierRegex = Pattern.compile("^claude-([a-z]+)-\\d");
        p.envPrefix = "ANTHROPIC";
        p.defaultContext = 200000;
        p.defaultOutput = 64000;
        return p;
    }

    // -- normalizeChain -----------------------------------------------------

    @Test
    void normalizeChain_legacyObject_becomesSingleElementList() {
        List<Assignment> out = ModelMap.normalizeChain(Map.of("provider", "antigravity", "model", "m-opus"));
        assertEquals(1, out.size());
        assertEquals("antigravity", out.get(0).provider);
        assertEquals("m-opus", out.get(0).model);
    }

    @Test
    void normalizeChain_listWithInvalidEntry_filtersItOut() {
        List<Assignment> out = ModelMap.normalizeChain(List.of(
                Map.of("provider", "antigravity", "model", "m-opus"),
                Map.of("provider", "antigravity") // missing model -> invalid
        ));
        assertEquals(1, out.size());
        assertEquals("m-opus", out.get(0).model);
    }

    @Test
    void normalizeChain_null_returnsEmptyList() {
        List<Assignment> out = ModelMap.normalizeChain(null);
        assertNotNull(out);
        assertTrue(out.isEmpty());
    }

    // -- readModelMap ---------------------------------------------------------

    @Test
    void readModelMap_readsModelMapFieldFromConfigFile() {
        InMemoryStore store = new InMemoryStore();
        TestJsonCodec json = new TestJsonCodec();
        RoutingProfile p = testProfile();
        store.put(p.configFile, "{\"modelMap\":{\"opus\":{\"provider\":\"antigravity\",\"model\":\"m-opus\"}}}");

        Map<String, Object> map = ModelMap.readModelMap(store, json, p);
        assertTrue(map.containsKey("opus"));
    }

    @Test
    void readModelMap_missingFile_returnsEmptyMap() {
        InMemoryStore store = new InMemoryStore();
        TestJsonCodec json = new TestJsonCodec();
        Map<String, Object> map = ModelMap.readModelMap(store, json, testProfile());
        assertNotNull(map);
        assertTrue(map.isEmpty());
    }

    // -- catalogEntries ---------------------------------------------------------

    @Test
    void catalogEntries_readsFromModelsCache() {
        InMemoryStore store = new InMemoryStore();
        TestJsonCodec json = new TestJsonCodec();
        store.put("models.json", "{\"antigravity\":{\"models\":{\"m-opus\":{\"name\":\"M Opus\",\"limit\":{\"context\":200000,\"output\":8192}}},\"ranking\":[\"m-opus\"]}}");

        List<CatalogEntry> entries = ModelMap.catalogEntries(store, json, List.of("antigravity"));
        assertEquals(1, entries.size());
        assertEquals("antigravity", entries.get(0).provider);
        assertEquals("m-opus", entries.get(0).model);
        assertEquals("M Opus", entries.get(0).name);
        assertEquals(200000, entries.get(0).contextLimit);
        assertEquals(8192, entries.get(0).outputLimit);
    }

    @Test
    void catalogEntries_providerWithNoCache_isSkipped() {
        InMemoryStore store = new InMemoryStore();
        TestJsonCodec json = new TestJsonCodec();
        List<CatalogEntry> entries = ModelMap.catalogEntries(store, json, List.of("unknown-provider"));
        assertTrue(entries.isEmpty());
    }

    // -- resolveModelMap ---------------------------------------------------------

    @Test
    void resolveModelMap_honorsExplicitTierMapping_andDefaultIsNonNull() {
        InMemoryStore store = new InMemoryStore();
        TestJsonCodec json = new TestJsonCodec();
        RoutingProfile p = testProfile();
        store.put(p.configFile, "{\"modelMap\":{\"opus\":{\"provider\":\"antigravity\",\"model\":\"m-opus\"}}}");
        store.put("models.json", "{\"antigravity\":{\"models\":{\"m-opus\":{\"name\":\"M Opus\"}},\"ranking\":[\"m-opus\"]}}");

        Map<String, List<Assignment>> map = ModelMap.resolveModelMap(store, json, p);

        assertNotNull(map.get("opus"));
        assertFalse(map.get("opus").isEmpty());
        assertEquals("antigravity", map.get("opus").get(0).provider);
        assertEquals("m-opus", map.get("opus").get(0).model);

        assertNotNull(map.get("default"));
    }

    @Test
    void resolveModelMap_staleProviderModel_healsWithinSameProvider() {
        InMemoryStore store = new InMemoryStore();
        TestJsonCodec json = new TestJsonCodec();
        RoutingProfile p = testProfile();
        // stored mapping points at a model that no longer exists for antigravity
        store.put(p.configFile, "{\"modelMap\":{\"opus\":{\"provider\":\"antigravity\",\"model\":\"m-opus-old\"}}}");
        // the live catalog now only has m-opus-new for antigravity
        store.put("models.json", "{\"antigravity\":{\"models\":{\"m-opus-new\":{\"name\":\"M Opus New\"}},\"ranking\":[\"m-opus-new\"]}}");

        Map<String, List<Assignment>> map = ModelMap.resolveModelMap(store, json, p);

        List<Assignment> opus = map.get("opus");
        assertNotNull(opus);
        assertFalse(opus.isEmpty());
        assertEquals("antigravity", opus.get(0).provider);
        assertEquals("m-opus-new", opus.get(0).model);
        assertTrue(opus.get(0).derived);
    }

    // -- modelEnvPairs ---------------------------------------------------------

    @Test
    void modelEnvPairs_containsDefaultOpusModel() {
        InMemoryStore store = new InMemoryStore();
        TestJsonCodec json = new TestJsonCodec();
        RoutingProfile p = testProfile();
        store.put(p.configFile, "{\"modelMap\":{\"opus\":{\"provider\":\"antigravity\",\"model\":\"m-opus\"}}}");
        store.put("models.json", "{\"antigravity\":{\"models\":{\"m-opus\":{\"name\":\"M Opus\"}},\"ranking\":[\"m-opus\"]}}");

        List<ModelMap.KV> pairs = ModelMap.modelEnvPairs(store, json, p);

        boolean found = false;
        for (ModelMap.KV kv : pairs) {
            if ("ANTHROPIC_DEFAULT_OPUS_MODEL".equals(kv.key)) {
                assertEquals("m-opus", kv.value);
                found = true;
            }
        }
        assertTrue(found, "expected ANTHROPIC_DEFAULT_OPUS_MODEL in " + pairs);
    }
}
