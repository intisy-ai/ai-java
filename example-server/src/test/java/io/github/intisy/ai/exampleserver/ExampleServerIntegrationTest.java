package io.github.intisy.ai.exampleserver;

import io.github.intisy.ai.exampleserver.admin.AccountAdmin;
import io.github.intisy.ai.exampleserver.admin.MessagesAdmin;
import io.github.intisy.ai.exampleserver.admin.RoutingAdmin;
import io.github.intisy.ai.exampleserver.api.ManagementApi;
import io.github.intisy.ai.exampleserver.discovery.ProviderDiscovery;
import io.github.intisy.ai.exampleserver.discovery.ProviderRegistryHolder;
import io.github.intisy.ai.jvm.AiJava;
import io.github.intisy.ai.jvm.Storage;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Boots {@link ExampleServer} (no router/{@code /v1} of its own -- see the class javadoc) with the
 * direct-chat surface wired in ({@link MessagesAdmin}, the full 15-arg {@link ManagementApi}
 * constructor) and drives {@code POST /api/providers/{id}/messages} + {@code GET
 * /api/routing/catalog} over loopback: console chat is a DIRECT provider call, never a router
 * match. The echo provider is staged the same way {@link ConfigApiIntegrationTest} does.
 */
class ExampleServerIntegrationTest {

    private static final String CONFIG_FILE = "example-server-routing.json";

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
        RoutingAdmin routing = new RoutingAdmin(store, json, holder, ai.logger());
        MessagesAdmin messages = new MessagesAdmin(store, json, holder, ai.logger());
        ManagementApi api = new ManagementApi(holder::listProviderIds, admin, json, null, null, holder,
                routing, null, null, null, null, null, null, null, messages);

        server = ExampleServer.start(0, api); // ephemeral port
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
    void postMessagesCallsProviderDirectly() throws IOException {
        String body = "{\"model\":\"m-echo-haiku\",\"messages\":[]}";
        Response r = post("/api/providers/echo/messages", body);
        assertEquals(200, r.status);
        assertTrue(r.body.contains("Echo provider handled your request"), r.body);
        assertTrue(r.body.contains("m-echo-haiku"), r.body);
    }

    @Test
    void postMessagesUnknownProviderIsNotFoundErrorShape() throws IOException {
        Response r = post("/api/providers/does-not-exist/messages", "{\"model\":\"x\",\"messages\":[]}");
        assertEquals(404, r.status, r.body);
        assertTrue(r.body.contains("not_found"), r.body);
    }

    @Test
    void getCatalogReturnsSeededModels() throws IOException {
        Response r = get("/api/routing/catalog");
        assertEquals(200, r.status);
        assertTrue(r.body.contains("m-echo-haiku"), r.body);
    }

    @Test
    void healthzIsLiveWithoutRouting() throws IOException {
        Response r = get("/healthz");
        assertEquals(200, r.status);
        assertTrue(r.body.contains("ok"), r.body);
    }

    @Test
    void noBuiltInV1Endpoint() throws IOException {
        // ExampleServer carries no router/`/v1` at all anymore -- both paths must 404 (the
        // dashboard's own "/" context, which never matches these prefixes).
        assertEquals(404, get("/v1/models").status);
        assertEquals(404, post("/v1/messages", "{}").status);
    }

    // -- tiny loopback HTTP client (test-only; newer JDK APIs allowed in tests) --

    private Response get(String path) throws IOException {
        HttpURLConnection c = open(path, "GET");
        return read(c);
    }

    private Response post(String path, String body) throws IOException {
        HttpURLConnection c = open(path, "POST");
        c.setDoOutput(true);
        c.setRequestProperty("content-type", "application/json");
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
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
