package io.github.intisy.ai.exampleserver;

import io.github.intisy.ai.exampleserver.admin.AccountAdmin;
import io.github.intisy.ai.exampleserver.api.ManagementApi;
import io.github.intisy.ai.jvm.AiJava;
import io.github.intisy.ai.jvm.Storage;
import io.github.intisy.ai.shared.model.Account;
import io.github.intisy.ai.shared.routing.RoutingProfile;
import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.Store;
import io.github.intisy.ai.shared.store.AccountStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Boots {@link ExampleServer} with the {@code /api} management context wired in and drives it
 *  over loopback: provider listing, account listing, enable/disable, and removal. */
class ManagementApiIntegrationTest {

    private static final String CONFIG_FILE = "management-api-routing.json";

    private AiJava ai;
    private ExampleServer server;
    private AccountStore accountStore;

    @BeforeEach
    void setUp() {
        ai = AiJava.builder().storage(Storage.memory()).build();
        Store store = ai.store();
        JsonCodec json = ai.jsonCodec();
        ServerSeeds.seedEcho(store, json, CONFIG_FILE);

        accountStore = new AccountStore(store, json);
        accountStore.add("echo", account("acc1", "acc1@example.com"));
        accountStore.add("echo", account("acc2", "acc2@example.com"));

        AccountAdmin admin = new AccountAdmin(accountStore, ai.clock());
        ManagementApi api = new ManagementApi(() -> Arrays.asList("echo"), admin, json);

        RoutingProfile profile = ServerProfile.echoTiers(CONFIG_FILE);
        server = ExampleServer.start(ai, profile, 0, api); // ephemeral port
    }

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) server.stop();
        if (ai != null) ai.close();
    }

    private static Account account(String id, String email) {
        Account a = new Account();
        a.id = id;
        a.email = email;
        a.enabled = true;
        return a;
    }

    @Test
    void listsProvidersWithAccountCounts() throws IOException {
        Response r = get("/api/providers");
        assertEquals(200, r.status);
        assertTrue(r.body.contains("\"echo\""), r.body);
        assertTrue(r.body.contains("\"accounts\":2") || r.body.contains("\"accounts\": 2"), r.body);
    }

    @Test
    void listsSeededAccountsForProvider() throws IOException {
        Response r = get("/api/providers/echo/accounts");
        assertEquals(200, r.status);
        assertTrue(r.body.contains("acc1"), r.body);
        assertTrue(r.body.contains("acc2"), r.body);
        assertTrue(r.body.contains("ready"), r.body);
    }

    @Test
    void disableThenShowsDisabledStatus() throws IOException {
        Response disable = post("/api/providers/echo/accounts/acc1/disable");
        assertEquals(204, disable.status);

        Response r = get("/api/providers/echo/accounts");
        assertEquals(200, r.status);
        assertTrue(r.body.contains("disabled"), r.body);
    }

    @Test
    void enableFlipsBackToReady() throws IOException {
        post("/api/providers/echo/accounts/acc1/disable");
        Response enable = post("/api/providers/echo/accounts/acc1/enable");
        assertEquals(204, enable.status);

        Response r = get("/api/providers/echo/accounts");
        assertEquals(200, r.status);
        assertTrue(!r.body.contains("\"disabled\""), r.body);
    }

    @Test
    void deleteRemovesAccount() throws IOException {
        Response del = delete("/api/providers/echo/accounts/acc1");
        assertEquals(204, del.status);

        Response r = get("/api/providers/echo/accounts");
        assertEquals(200, r.status);
        assertTrue(!r.body.contains("acc1"), r.body);
        assertTrue(r.body.contains("acc2"), r.body);
    }

    @Test
    void postAccountsSeedsAccountAndAppearsInSubsequentList() throws IOException {
        String requestBody = "{\"refresh\":\"REFRESH123\",\"email\":\"seeded@example.com\","
                + "\"projectId\":\"proj-1\",\"managedProjectId\":\"managed-9\"}";
        Response created = post("/api/providers/echo/accounts", requestBody);
        assertEquals(200, created.status);
        assertTrue(created.body.contains("seeded@example.com"), created.body);
        assertTrue(!created.body.contains("REFRESH123"), created.body); // secret never in the view

        Response listed = get("/api/providers/echo/accounts");
        assertEquals(200, listed.status);
        assertTrue(listed.body.contains("seeded@example.com"), listed.body);
    }

    @Test
    void postAccountsMissingRefreshIs400() throws IOException {
        Response r = post("/api/providers/echo/accounts", "{\"email\":\"noref@example.com\"}");
        assertEquals(400, r.status);
        assertTrue(r.body.contains("error"), r.body);
    }

    @Test
    void unknownApiPathIs404Json() throws IOException {
        Response r = get("/api/nope");
        assertEquals(404, r.status);
        assertTrue(r.body.contains("not found"), r.body);
    }

    @Test
    void healthzStillWorksAlongsideApi() throws IOException {
        Response r = get("/healthz");
        assertEquals(200, r.status);
        assertTrue(r.body.contains("ok"), r.body);
    }

    // -- tiny loopback HTTP client (test-only; newer JDK APIs allowed in tests) --

    private Response get(String path) throws IOException {
        HttpURLConnection c = open(path, "GET");
        return read(c);
    }

    private Response post(String path) throws IOException {
        return post(path, null);
    }

    private Response post(String path, String body) throws IOException {
        HttpURLConnection c = open(path, "POST");
        c.setDoOutput(true);
        if (body != null) c.setRequestProperty("content-type", "application/json");
        try (OutputStream os = c.getOutputStream()) {
            os.write(body != null ? body.getBytes(StandardCharsets.UTF_8) : new byte[0]);
        }
        return read(c);
    }

    private Response delete(String path) throws IOException {
        HttpURLConnection c = open(path, "DELETE");
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
