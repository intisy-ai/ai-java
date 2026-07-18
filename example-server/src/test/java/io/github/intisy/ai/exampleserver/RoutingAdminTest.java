package io.github.intisy.ai.exampleserver;

import io.github.intisy.ai.exampleserver.admin.RoutingAdmin;
import io.github.intisy.ai.exampleserver.discovery.ProviderDiscovery;
import io.github.intisy.ai.exampleserver.discovery.ProviderRegistryHolder;
import io.github.intisy.ai.jvm.backend.json.GsonJsonCodec;
import io.github.intisy.ai.jvm.backend.store.InMemoryStore;
import io.github.intisy.ai.shared.logic.ModelMap;
import io.github.intisy.ai.shared.routing.RoutingProfile;
import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.Store;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises {@link RoutingAdmin} against a REAL jar-discovered provider pair (echo + ratelimited,
 * staged the same way {@code ProviderInstallIntegrationTest} does) rather than a hand-rolled fake:
 * {@code EchoProvider} answers {@code GET /v1/models} with a canned catalog (added alongside this
 * task) and {@code AlwaysRateLimitedProvider} always 429s, giving a real 2xx and a real non-2xx
 * discovery response with no network involved.
 */
class RoutingAdminTest {
    private static final String CONFIG_FILE = "routing-admin-test-routing.json";

    private Store store;
    private JsonCodec json;
    private ProviderRegistryHolder holder;
    private RoutingAdmin routing;

    @BeforeEach
    void setUp(@TempDir Path providersDir) throws IOException {
        store = new InMemoryStore();
        json = new GsonJsonCodec();
        ServerSeeds.seedEcho(store, json, CONFIG_FILE);

        stageProviderJar(providersDir);
        holder = new ProviderRegistryHolder(ProviderDiscovery.resolve(providersDir));
        assertTrue(holder.listProviderIds().contains("echo"), holder.listProviderIds().toString());
        assertTrue(holder.listProviderIds().contains("ratelimited"), holder.listProviderIds().toString());

        RoutingProfile profile = ServerProfile.echoTiers(CONFIG_FILE);
        routing = new RoutingAdmin(store, json, profile, holder, msg -> { });
    }

    @AfterEach
    void tearDown() throws IOException {
        // Releases the URLClassLoader backing the jar copied into @TempDir, or its cleanup fails
        // on Windows (file still in use) -- same reasoning as ProviderRegistryHolderTest.
        if (holder != null && holder.get() != null) holder.get().close();
    }

    private static void stageProviderJar(Path targetDir) throws IOException {
        String staged = System.getProperty("exampleserver.providersDir");
        assertNotNull(staged, "exampleserver.providersDir must be set by the Gradle test task");
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(Path.of(staged), "*.jar")) {
            for (Path jar : stream) {
                Files.copy(jar, targetDir.resolve(jar.getFileName()));
                return;
            }
        }
        fail("no staged provider jar found in " + staged);
    }

    @Test
    void discoverMergesModelsAndPreservesOtherProviders() {
        Map<String, Object> result = routing.discover("echo");
        assertEquals("echo", result.get("provider"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> models = (List<Map<String, Object>>) result.get("models");
        assertEquals(2, models.size());
        assertEquals("m-echo-opus", models.get(0).get("id"));
        assertEquals("Echo Opus", models.get(0).get("name"));

        Map<String, Object> catalog = asMap(json.parse(store.get("models.json")));
        assertTrue(catalog.containsKey("echo"));
        assertTrue(catalog.containsKey("ratelimited"), "seeded 'ratelimited' entry must be preserved");
    }

    @Test
    void discoverUnknownProviderThrows() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> routing.discover("does-not-exist"));
        assertTrue(e.getMessage().contains("does-not-exist"), e.getMessage());
    }

    // "ratelimited" (AlwaysRateLimitedProvider) implements Provider only, no ModelCatalogProvider --
    // discover is an explicit user action, so erroring on an absent capability is fine.
    @Test
    void discoverOfBareProviderThrows() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> routing.discover("ratelimited"));
        assertTrue(e.getMessage().contains("provider has no model catalog: ratelimited"), e.getMessage());
    }

    @Test
    void modelMapViewReturnsTiersAndSeededMap() {
        Map<String, Object> view = routing.modelMapView();
        assertEquals(Arrays.asList("opus", "sonnet", "haiku"), view.get("tiers"));

        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) view.get("map");
        assertTrue(map.containsKey("opus"));
        assertTrue(map.containsKey("haiku"));
        assertTrue(map.containsKey("sonnet"));
    }

    // Union: a declared-but-not-yet-discovered slot (fable) must appear (an operator can
    // pre-configure it before its first model is ever discovered), AND a detected-but-undeclared
    // tier (extra, from the catalog) must be appended after -- declared order first, de-duped.
    @Test
    void modelMapViewUnionsDeclaredTierOrderWithDetectedCatalogTiersInclFable() {
        String unionConfigFile = "routing-admin-test-union-routing.json";
        Store unionStore = new InMemoryStore();
        JsonCodec unionJson = new GsonJsonCodec();

        Map<String, Object> models = new LinkedHashMap<>();
        models.put("claude-opus-4", modelEntry("Opus"));
        models.put("claude-extra-1", modelEntry("Extra"));
        Map<String, Object> providerEntry = new LinkedHashMap<>();
        providerEntry.put("models", models);
        providerEntry.put("ranking", Arrays.asList("claude-opus-4", "claude-extra-1"));
        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("echo", providerEntry);
        unionStore.put("models.json", unionJson.stringify(catalog));

        RoutingProfile profile = ServerProfile.echoTiers(unionConfigFile);
        // "fable" is declared (tierOrder) but has no model in the catalog above -- it must still
        // surface as a pre-configurable slot.
        profile.tierOrder = Arrays.asList("opus", "sonnet", "haiku", "fable");

        RoutingAdmin unionRouting = new RoutingAdmin(unionStore, unionJson, profile, holder, msg -> { });
        Map<String, Object> view = unionRouting.modelMapView(profile);

        assertEquals(Arrays.asList("opus", "sonnet", "haiku", "fable", "extra"), view.get("tiers"));
    }

    private static Map<String, Object> modelEntry(String displayName) {
        Map<String, Object> limit = new LinkedHashMap<>();
        limit.put("context", 200000);
        limit.put("output", 64000);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", displayName);
        entry.put("limit", limit);
        return entry;
    }

    @Test
    void putModelMapRoundTripsThroughModelMapReadModelMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("haiku", Collections.singletonList(assignment("echo", "m-echo-haiku")));

        Map<String, Object> result = routing.putModelMap(map);
        assertEquals(Boolean.TRUE, result.get("ok"));
        assertTrue(((List<?>) result.get("warnings")).isEmpty());

        RoutingProfile profile = ServerProfile.echoTiers(CONFIG_FILE);
        Map<String, Object> reread = ModelMap.readModelMap(store, json, profile);
        assertTrue(reread.containsKey("haiku"));
    }

    @Test
    void putModelMapUnknownProviderThrows() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("haiku", Collections.singletonList(assignment("nope", "m-x")));

        assertThrows(IllegalArgumentException.class, () -> routing.putModelMap(map));
    }

    @Test
    void putModelMapUnknownModelWarnsButSucceeds() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("haiku", Collections.singletonList(assignment("echo", "m-does-not-exist")));

        Map<String, Object> result = routing.putModelMap(map);
        assertEquals(Boolean.TRUE, result.get("ok"));
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) result.get("warnings");
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("echo"));
        assertTrue(warnings.get(0).contains("m-does-not-exist"));
    }

    private static Map<String, Object> assignment(String provider, String model) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("provider", provider);
        entry.put("model", model);
        return entry;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }
}
