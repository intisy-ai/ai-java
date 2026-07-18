package io.github.intisy.ai.exampleserver;

import io.github.intisy.ai.exampleserver.admin.ConfigAdmin;
import io.github.intisy.ai.exampleserver.discovery.ProviderDiscovery;
import io.github.intisy.ai.exampleserver.discovery.ProviderRegistryHolder;
import io.github.intisy.ai.jvm.backend.json.GsonJsonCodec;
import io.github.intisy.ai.jvm.backend.store.InMemoryStore;
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
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Exercises {@link ConfigAdmin} against the same REAL jar-discovered {@code echo} provider
 * {@link QuotaAdminTest} uses, staged the same way. {@code EchoProvider} answers
 * {@code GET/PUT /v1/config} with a canned schema + in-memory values (added alongside this task),
 * giving a real round-trip with no network involved.
 */
class ConfigAdminTest {

    private Store store;
    private JsonCodec json;
    private ProviderRegistryHolder holder;
    private ConfigAdmin config;

    @BeforeEach
    void setUp(@TempDir Path providersDir) throws IOException {
        store = new InMemoryStore();
        json = new GsonJsonCodec();

        stageProviderJar(providersDir);
        holder = new ProviderRegistryHolder(ProviderDiscovery.resolve(providersDir));
        assertTrue(holder.listProviderIds().contains("echo"), holder.listProviderIds().toString());
        assertTrue(holder.listProviderIds().contains("ratelimited"), holder.listProviderIds().toString());

        config = new ConfigAdmin(store, json, holder, msg -> { });
    }

    @AfterEach
    void tearDown() throws IOException {
        // Releases the URLClassLoader backing the jar copied into @TempDir, or its cleanup fails
        // on Windows (file still in use) -- same reasoning as RoutingAdminTest/QuotaAdminTest.
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
    void getConfigReturnsGroupsAndValues() {
        Map<String, Object> result = config.getConfig("echo");
        assertNotNull(result.get("groups"));
        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) result.get("values");
        assertEquals("Echo provider handled your request", values.get("greeting"));
    }

    @Test
    void putConfigPersistsAndIsReadBack() {
        Map<String, Object> newValues = new LinkedHashMap<>();
        newValues.put("greeting", "hello from test");
        newValues.put("verbose", true);
        config.putConfig("echo", newValues);

        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) config.getConfig("echo").get("values");
        assertEquals("hello from test", values.get("greeting"));
        assertEquals(Boolean.TRUE, values.get("verbose"));
    }

    @Test
    void getConfigUnknownProviderThrows() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> config.getConfig("nope"));
        assertTrue(e.getMessage().contains("unknown provider: nope"), e.getMessage());
    }

    // -- absent-capability: "ratelimited" (AlwaysRateLimitedProvider) implements Provider only --

    @Test
    void getConfigOfBareProviderReturnsNull() {
        assertEquals(null, config.getConfig("ratelimited"));
    }

    @Test
    void putConfigOfBareProviderThrows() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> config.putConfig("ratelimited", new LinkedHashMap<>()));
        assertTrue(e.getMessage().contains("provider has no config surface: ratelimited"), e.getMessage());
    }
}
