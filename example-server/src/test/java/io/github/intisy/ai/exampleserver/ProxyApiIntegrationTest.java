package io.github.intisy.ai.exampleserver;

import io.github.intisy.ai.exampleserver.admin.AccountAdmin;
import io.github.intisy.ai.exampleserver.admin.ProxyAdmin;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Boots {@link ExampleServer} with the proxy-management surface wired in (the full 11-arg
 * {@link ManagementApi} constructor, with {@link ProxyAdmin} backed by a real {@link ProxyManager})
 * and drives {@code /api/proxies*} over loopback, mirroring {@link QuotaApiIntegrationTest}'s
 * harness style. A file-backed store is used (not memory) so {@code proxies.json} round-trips the
 * way it would in a real boot. The test actually starts a proxy on an ephemeral port (bound via
 * {@code {"port":0}}, never a fixed port, so the test is hermetic) and confirms it serves
 * {@code /healthz} for real before stopping it again.
 */
class ProxyApiIntegrationTest {

    private static final String CONFIG_FILE = "proxy-api-routing.json";
    private static final Pattern PORT_PATTERN = Pattern.compile("\"port\"\\s*:\\s*(\\d+)");

    private AiJava ai;
    private ExampleServer server;
    private ProviderRegistryHolder holder;
    private ProxyManager proxyManager;

    @BeforeEach
    void setUp(@TempDir Path providersDir, @TempDir Path configDir) throws IOException {
        stageProviderJar(providersDir);

        ai = AiJava.builder().storage(Storage.file(configDir)).build();
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
        proxyManager = new ProxyManager(ai, holder, store, json, ai.logger());
        ProxyAdmin proxyAdmin = new ProxyAdmin(proxyManager);
        ManagementApi api = new ManagementApi(holder::listProviderIds, admin, json, null, null, holder,
                routing, quota, null, null, proxyAdmin);

        AiJava.WiredRouter router = ai.router(profile,
                id -> holder.asHandlerResolver().resolve(id), holder::listProviderIds);
        server = ExampleServer.start(router, 0, api); // ephemeral port
    }

    @AfterEach
    void tearDown() throws IOException {
        if (proxyManager != null) proxyManager.stopAll(); // release any ephemeral proxy port
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
    void listReturnsClaudeCode() throws IOException {
        Response r = get("/api/proxies");
        assertEquals(200, r.status, r.body);
        assertTrue(r.body.contains("claude-code"), r.body);
    }

    @Test
    void putPortStartThenStop() throws IOException {
        assertEquals(200, put("/api/proxies/claude-code", "{\"port\":0}").status); // 0 -> ephemeral
        Response started = post("/api/proxies/claude-code/start");
        assertEquals(200, started.status, started.body);
        assertTrue(started.body.contains("\"running\":true"), started.body);
        int port = extractPort(started.body);
        assertEquals(200, healthz(port));            // the started proxy actually serves
        assertEquals(200, post("/api/proxies/claude-code/stop").status);
    }

    @Test
    void unknownAppIs400() throws IOException {
        assertEquals(400, post("/api/proxies/nope/start").status);
    }

    private static int extractPort(String body) {
        Matcher m = PORT_PATTERN.matcher(body);
        assertTrue(m.find(), body);
        return Integer.parseInt(m.group(1));
    }

    private static int healthz(int port) throws IOException {
        URL url = new URL("http://127.0.0.1:" + port + "/healthz");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("GET");
        return c.getResponseCode();
    }

    // -- tiny loopback HTTP client (test-only; mirrors QuotaApiIntegrationTest's helper) --

    private Response get(String path) throws IOException {
        return read(open(path, "GET"));
    }

    private Response put(String path, String body) throws IOException {
        HttpURLConnection c = open(path, "PUT");
        c.setDoOutput(true);
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return read(c);
    }

    private Response post(String path) throws IOException {
        HttpURLConnection c = open(path, "POST");
        c.setDoOutput(true);
        try (OutputStream os = c.getOutputStream()) {
            os.write(new byte[0]);
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
