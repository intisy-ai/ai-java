package io.github.intisy.ai.exampleserver;

import io.github.intisy.ai.exampleserver.admin.AccountAdmin;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Boots {@link ExampleServer} with the routing surface wired in (the 7-arg {@link ManagementApi}
 * constructor) and drives the discover/catalog/model-map endpoints over loopback, mirroring
 * {@link ManagementApiIntegrationTest}'s harness style. The echo + ratelimited providers are
 * staged the same way {@link ProviderInstallIntegrationTest} does, giving a real 2xx and a real
 * non-2xx discovery response with no network involved.
 */
class RoutingApiIntegrationTest {

    private static final String CONFIG_FILE = "routing-api-routing.json";

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
        ManagementApi api = new ManagementApi(holder::listProviderIds, admin, json, null, null, holder, routing);

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
    void discoverMergesModelsIntoCatalog() throws IOException {
        Response r = post("/api/providers/echo/models/discover");
        assertEquals(200, r.status, r.body);
        assertTrue(r.body.contains("m-echo-opus"), r.body);
        assertTrue(r.body.contains("Echo Opus"), r.body);
    }

    @Test
    void discoverNon2xxIs400() throws IOException {
        Response r = post("/api/providers/ratelimited/models/discover");
        assertEquals(400, r.status, r.body);
        assertTrue(r.body.contains("429"), r.body);
    }

    @Test
    void discoverUnknownProviderIs400() throws IOException {
        Response r = post("/api/providers/does-not-exist/models/discover");
        assertEquals(400, r.status, r.body);
        assertTrue(r.body.contains("error"), r.body);
    }

    @Test
    void getCatalogReturnsSeededModels() throws IOException {
        Response r = get("/api/routing/catalog");
        assertEquals(200, r.status, r.body);
        assertTrue(r.body.contains("echo"), r.body);
        assertTrue(r.body.contains("ratelimited"), r.body);
    }

    @Test
    void getModelMapReturnsTiersAndSeededMap() throws IOException {
        Response r = get("/api/routing/model-map");
        assertEquals(200, r.status, r.body);
        assertTrue(r.body.contains("\"tiers\""), r.body);
        assertTrue(r.body.contains("opus"), r.body);
        assertTrue(r.body.contains("\"map\""), r.body);
    }

    @Test
    void putModelMapRoundTrips() throws IOException {
        String body = "{\"map\":{\"haiku\":[{\"provider\":\"echo\",\"model\":\"m-echo-haiku\"}]}}";
        Response put = put("/api/routing/model-map", body);
        assertEquals(200, put.status, put.body);
        assertTrue(put.body.contains("\"ok\":true") || put.body.contains("\"ok\": true"), put.body);

        Response after = get("/api/routing/model-map");
        assertEquals(200, after.status, after.body);
        assertTrue(after.body.contains("m-echo-haiku"), after.body);
    }

    @Test
    void putModelMapUnknownProviderIs400() throws IOException {
        String body = "{\"map\":{\"haiku\":[{\"provider\":\"nope\",\"model\":\"m-x\"}]}}";
        Response put = put("/api/routing/model-map", body);
        assertEquals(400, put.status, put.body);
        assertTrue(put.body.contains("error"), put.body);
    }

    @Test
    void modelMapIsAppScoped() throws IOException {
        // Save a map for claude-code, and a different one for opencode; each reads back its own.
        assertEquals(200, put("/api/routing/model-map?app=claude-code",
                "{\"map\":{\"opus\":[{\"provider\":\"echo\",\"model\":\"m-echo-opus\"}]}}").status);
        assertEquals(200, put("/api/routing/model-map?app=opencode",
                "{\"map\":{\"haiku\":[{\"provider\":\"echo\",\"model\":\"m-echo-haiku\"}]}}").status);

        Response cc = get("/api/routing/model-map?app=claude-code");
        assertTrue(cc.body.contains("m-echo-opus"), cc.body);
        assertFalse(cc.body.contains("m-echo-haiku"), cc.body);

        Response oc = get("/api/routing/model-map?app=opencode");
        assertTrue(oc.body.contains("m-echo-haiku"), oc.body);
        assertFalse(oc.body.contains("m-echo-opus"), oc.body);
    }

    @Test
    void modelMapWithoutAppUsesDefaultProfile() throws IOException {
        // Back-compat: no app param still round-trips against the default (demo) profile.
        Response r = get("/api/routing/model-map");
        assertEquals(200, r.status, r.body);
    }

    // -- tiny loopback HTTP client (test-only; mirrors ManagementApiIntegrationTest's helper) --

    private Response get(String path) throws IOException {
        return read(open(path, "GET"));
    }

    private Response post(String path) throws IOException {
        HttpURLConnection c = open(path, "POST");
        c.setDoOutput(true);
        try (OutputStream os = c.getOutputStream()) {
            os.write(new byte[0]);
        }
        return read(c);
    }

    private Response put(String path, String body) throws IOException {
        HttpURLConnection c = open(path, "PUT");
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
