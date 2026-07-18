package io.github.intisy.ai.exampleserver;

import io.github.intisy.ai.exampleserver.admin.MessagesAdmin;
import io.github.intisy.ai.exampleserver.discovery.ProviderDiscovery;
import io.github.intisy.ai.exampleserver.discovery.ProviderRegistryHolder;
import io.github.intisy.ai.jvm.backend.json.GsonJsonCodec;
import io.github.intisy.ai.jvm.backend.store.InMemoryStore;
import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.Store;
import io.github.intisy.ai.shared.spi.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Exercises {@link MessagesAdmin} against the same REAL jar-discovered {@code echo} provider
 * {@link ConfigAdminTest}/{@link QuotaAdminTest} use, staged the same way: a DIRECT {@code
 * Provider#handle} call, no router, no model-&gt;provider resolution. {@code EchoProvider} answers
 * with an Anthropic-messages-shaped body echoing back {@code HandlerCtx#model}, giving a real
 * round-trip with no fabricated HTTP request and no network involved.
 */
class MessagesAdminTest {

    private Store store;
    private JsonCodec json;
    private ProviderRegistryHolder holder;
    private MessagesAdmin messages;

    @BeforeEach
    void setUp(@TempDir Path providersDir) throws IOException {
        store = new InMemoryStore();
        json = new GsonJsonCodec();

        stageProviderJar(providersDir);
        holder = new ProviderRegistryHolder(ProviderDiscovery.resolve(providersDir));
        assertTrue(holder.listProviderIds().contains("echo"), holder.listProviderIds().toString());

        messages = new MessagesAdmin(store, json, holder, msg -> { });
    }

    @AfterEach
    void tearDown() throws IOException {
        // Releases the URLClassLoader backing the jar copied into @TempDir, or its cleanup fails
        // on Windows (file still in use) -- same reasoning as ConfigAdminTest/QuotaAdminTest.
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
    void sendCallsProviderDirectlyAndThreadsModelThroughHandlerCtx() {
        HttpResponse resp = messages.send("echo", "{\"model\":\"m-echo-haiku\",\"messages\":[]}");
        assertEquals(200, resp.status);
        assertTrue(resp.body.contains("Echo provider handled your request"), resp.body);
        assertTrue(resp.body.contains("m-echo-haiku"), resp.body);
    }

    @Test
    void sendWithMalformedBodyStillReachesProvider() {
        // EchoProvider never parses the body itself -- a malformed body must not block the DIRECT
        // call; modelOf() degrades to null, and the provider serves its own default.
        HttpResponse resp = messages.send("echo", "not json");
        assertEquals(200, resp.status);
        assertTrue(resp.body.contains("echo-default"), resp.body);
    }

    @Test
    void sendUnknownProviderIsAnthropicShaped404() {
        HttpResponse resp = messages.send("does-not-exist", "{\"model\":\"x\",\"messages\":[]}");
        assertEquals(404, resp.status);
        assertTrue(resp.body.contains("\"type\":\"error\""), resp.body);
        assertTrue(resp.body.contains("not_found"), resp.body);
        assertTrue(resp.body.contains("does-not-exist"), resp.body);
    }
}
