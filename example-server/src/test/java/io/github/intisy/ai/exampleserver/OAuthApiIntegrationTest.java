package io.github.intisy.ai.exampleserver;

import io.github.intisy.ai.exampleserver.admin.AccountAdmin;
import io.github.intisy.ai.exampleserver.admin.ConfigAdmin;
import io.github.intisy.ai.exampleserver.admin.OAuthAdmin;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Boots {@link ExampleServer} with the OAuth-login surface wired in (the full 10-arg {@link
 * ManagementApi} constructor) and drives {@code POST /api/providers/{id}/oauth/start} +
 * {@code GET /api/oauth/callback} over loopback, mirroring {@link QuotaApiIntegrationTest}'s
 * harness style. Because {@code start} returns an authorize URL pointing at the echo fixture's
 * fake {@code https://echo.example/authorize} (not reachable), this drives the callback directly
 * with the {@code state} extracted from the {@code start} response instead of opening a browser.
 */
class OAuthApiIntegrationTest {

    private static final String CONFIG_FILE = "oauth-api-routing.json";

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
        OAuthAdmin oauth = new OAuthAdmin(store, json, holder, ai.logger(), admin, ai.clock());
        ManagementApi api = new ManagementApi(holder::listProviderIds, admin, json, null, null, holder,
                routing, quota, config, oauth);

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
    void startReturnsAuthorizeUrl() throws IOException {
        Response r = post("/api/providers/echo/oauth/start");
        assertEquals(200, r.status, r.body);
        assertTrue(r.body.contains("echo-client-id"), r.body);
        assertTrue(r.body.contains("code_challenge_method=S256"), r.body);
        assertTrue(r.body.contains("\"state\""), r.body);
    }

    @Test
    void callbackWithValidStateSeedsAccountAndReturnsHtml() throws IOException {
        Response start = post("/api/providers/echo/oauth/start");
        String state = extractJsonString(start.body, "state");
        assertNotNull(state);

        Response cb = get("/api/oauth/callback?code=abc123&state=" + java.net.URLEncoder.encode(state, "UTF-8"));
        assertEquals(200, cb.status, cb.body);
        assertTrue(cb.body.toLowerCase().contains("close"), cb.body); // "you can close this tab"

        Response accounts = get("/api/providers/echo/accounts");
        assertTrue(accounts.body.contains("echo-oauth-user"), accounts.body);
    }

    @Test
    void callbackWithUnknownStateIs400() throws IOException {
        Response cb = get("/api/oauth/callback?code=abc&state=bogus");
        assertEquals(400, cb.status, cb.body);
    }

    private static String extractJsonString(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    // -- tiny loopback HTTP client (test-only; mirrors QuotaApiIntegrationTest's helper) --

    private Response post(String path) throws IOException {
        HttpURLConnection c = open(path, "POST");
        c.setDoOutput(true);
        try (OutputStream os = c.getOutputStream()) {
            os.write(new byte[0]);
        }
        return read(c);
    }

    private Response get(String path) throws IOException {
        return read(open(path, "GET"));
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
