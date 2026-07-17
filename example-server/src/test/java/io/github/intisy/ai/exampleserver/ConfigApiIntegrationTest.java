package io.github.intisy.ai.exampleserver;

import io.github.intisy.ai.exampleserver.admin.AccountAdmin;
import io.github.intisy.ai.exampleserver.admin.ConfigAdmin;
import io.github.intisy.ai.exampleserver.admin.QuotaAdmin;
import io.github.intisy.ai.exampleserver.admin.RoutingAdmin;
import io.github.intisy.ai.exampleserver.api.ManagementApi;
import io.github.intisy.ai.exampleserver.discovery.ProviderDiscovery;
import io.github.intisy.ai.exampleserver.discovery.ProviderRegistryHolder;
import io.github.intisy.ai.jvm.AiJava;
import io.github.intisy.ai.jvm.Storage;
import io.github.intisy.ai.shared.routing.RoutingProfile;
import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.Store;
import io.github.intisy.ai.shared.store.AccountStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Boots {@link ExampleServer} with the config surface wired in (the new 9-arg {@link
 * ManagementApi} constructor) and drives {@code GET}/{@code PUT /api/providers/{id}/config} over
 * loopback, mirroring {@link QuotaApiIntegrationTest}'s harness style. The echo provider is staged
 * the same way {@link ProviderInstallIntegrationTest} does, giving a real 2xx round-trip through
 * {@link ConfigAdmin} with no network involved.
 */
class ConfigApiIntegrationTest {

    private static final String CONFIG_FILE = "config-api-routing.json";

    private AiJava ai;
    private ExampleServer server;
    private ProviderRegistryHolder holder;

    @BeforeEach
    void setUp(@TempDir Path providersDir) throws IOException {
        stageProviderJar(providersDir);

        ai = AiJava.builder().storage(Storage.memory()).build();
        Store store = ai.store();
        JsonCodec json = ai.jsonCodec();
        ServerSeeds.seedEcho(store, json, CONFIG_FILE);

        holder = new ProviderRegistryHolder(ProviderDiscovery.resolve(providersDir));
        assertTrue(holder.listProviderIds().contains("echo"), holder.listProviderIds().toString());

        AccountStore accountStore = new AccountStore(store, json);
        AccountAdmin admin = new AccountAdmin(accountStore, ai.clock());

        RoutingProfile profile = ServerProfile.echoTiers(CONFIG_FILE);
        RoutingAdmin routing = new RoutingAdmin(store, json, profile, holder, ai.logger());
        QuotaAdmin quota = new QuotaAdmin(store, json, holder, ai.logger());
        ConfigAdmin config = new ConfigAdmin(store, json, holder, ai.logger());
        ManagementApi api = new ManagementApi(holder::listProviderIds, admin, json, null, null, holder,
                routing, quota, config);

        AiJava.WiredRouter router = ai.router(profile,
                id -> holder.asHandlerResolver().resolve(id), holder::listProviderIds);
        server = ExampleServer.start(router, 0, api); // ephemeral port
    }

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) server.stop();
        if (holder != null && holder.get() != null) holder.get().close();
        if (ai != null) ai.close();
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
    void getConfigReturnsSchema() throws IOException {
        Response r = get("/api/providers/echo/config");
        assertEquals(200, r.status, r.body);
        assertTrue(r.body.contains("\"groups\""), r.body);
        assertTrue(r.body.contains("greeting"), r.body);
    }

    @Test
    void putConfigRoundTrips() throws IOException {
        Response r = put("/api/providers/echo/config", "{\"values\":{\"greeting\":\"api hello\"}}");
        assertEquals(200, r.status, r.body);
        Response after = get("/api/providers/echo/config");
        assertTrue(after.body.contains("api hello"), after.body);
    }

    @Test
    void getConfigUnknownProviderIs400() throws IOException {
        Response r = get("/api/providers/does-not-exist/config");
        assertEquals(400, r.status, r.body);
    }

    // -- tiny loopback HTTP client (test-only; mirrors QuotaApiIntegrationTest's helper) --

    private Response get(String path) throws IOException {
        return read(open(path, "GET"));
    }

    private Response put(String path, String body) throws IOException {
        HttpURLConnection c = open(path, "PUT");
        c.setDoOutput(true);
        c.setRequestProperty("content-type", "application/json");
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return read(c);
    }

    private HttpURLConnection open(String path, String method) throws IOException {
        URL url = new URL("http://127.0.0.1:" + server.port() + path);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod(method);
        return c;
    }

    private Response read(HttpURLConnection c) throws IOException {
        int status = c.getResponseCode();
        InputStream is = status < 400 ? c.getInputStream() : c.getErrorStream();
        String text = "";
        if (is != null) {
            try (Scanner s = new Scanner(is, "UTF-8").useDelimiter("\\A")) {
                text = s.hasNext() ? s.next() : "";
            }
        }
        return new Response(status, text);
    }

    private static final class Response {
        final int status;
        final String body;
        Response(int status, String body) { this.status = status; this.body = body; }
    }
}
