package io.github.intisy.ai.exampleserver;

import io.github.intisy.ai.exampleserver.admin.ConfigAdmin;
import io.github.intisy.ai.exampleserver.admin.QuotaAdmin;
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
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Proves {@link QuotaAdmin}/{@link ConfigAdmin} (and, by the same one-line change,
 * {@code OAuthAdmin}/{@code RoutingAdmin}) thread the server's injected {@link Store} into
 * the {@code HandlerCtx} they build for a provider call -- not just {@code configDir}. Exercised
 * via the real jar-discovered {@code ctx-capture} example provider, staged the same way
 * {@link QuotaAdminTest}/{@link ConfigAdminTest}/{@link RoutingAdminTest} stage the jar: its
 * {@code handle} writes a fixed marker into {@code ctx.store} IFF that store is non-null. The
 * test's own {@code store} reference reads the marker back after the call -- if the admin
 * threaded a different (or no) store, the marker would never appear in it. The store here is a
 * plain {@code InMemoryStore} (not a {@code FileStore}), so {@code configDir} is empty and the
 * provider has no store of its own to fall back to: the marker's absence is a direct signal of a
 * store split-brain, not an artifact of some other fallback path.
 */
class AdminStoreThreadingTest {

    private Store store;
    private JsonCodec json;
    private ProviderRegistryHolder holder;

    @BeforeEach
    void setUp(@TempDir Path providersDir) throws IOException {
        store = new InMemoryStore();
        json = new GsonJsonCodec();

        stageProviderJar(providersDir);
        holder = new ProviderRegistryHolder(ProviderDiscovery.resolve(providersDir));
        assertTrue(holder.listProviderIds().contains("ctx-capture"), holder.listProviderIds().toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        // Releases the URLClassLoader backing the jar copied into @TempDir, or its cleanup fails
        // on Windows (file still in use) -- same reasoning as QuotaAdminTest/ConfigAdminTest.
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
    void quotaAdminThreadsServerStoreIntoProviderHandlerCtx() {
        assertNull(store.get("ctx-capture-marker"), "marker must not be pre-seeded");

        new QuotaAdmin(store, json, holder, msg -> { }).refresh("ctx-capture");

        assertEquals("seen", store.get("ctx-capture-marker"),
                "QuotaAdmin must pass the server's injected Store into HandlerCtx.store, not just configDir");
    }

    @Test
    void configAdminThreadsServerStoreIntoProviderHandlerCtx() {
        assertNull(store.get("ctx-capture-marker"), "marker must not be pre-seeded");

        new ConfigAdmin(store, json, holder, msg -> { }).putConfig("ctx-capture", Collections.emptyMap());

        assertEquals("seen", store.get("ctx-capture-marker"),
                "ConfigAdmin must pass the server's injected Store into HandlerCtx.store, not just configDir");
    }
}
