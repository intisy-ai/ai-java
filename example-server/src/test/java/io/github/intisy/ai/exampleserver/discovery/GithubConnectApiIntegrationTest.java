package io.github.intisy.ai.exampleserver.discovery;

import io.github.intisy.ai.exampleserver.ExampleServer;
import io.github.intisy.ai.exampleserver.admin.AccountAdmin;
import io.github.intisy.ai.exampleserver.api.ManagementApi;
import io.github.intisy.ai.jvm.AiJava;
import io.github.intisy.ai.jvm.Storage;
import io.github.intisy.ai.shared.spi.JsonCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link ManagementApi}'s {@code /api/github*} routes over real loopback HTTP, with {@link
 * GithubAuth} wired to fully controlled env/gh/http seams (package-private constructor -- this test
 * lives in {@code discovery} specifically to reach it) so nothing here depends on the real OS
 * environment or a real {@code gh}/network call, ever.
 *
 * <p>The central assertion, repeated after every mutation: NONE of the tokens used in these tests
 * ever appear anywhere in a response body -- status responses expose only
 * source/connected/login/rateLimitRemaining.
 */
class GithubConnectApiIntegrationTest {

    private AiJava ai;
    private ExampleServer server;
    private AtomicReference<String> ghOutput;
    private GithubAuth.Http fakeHttp;

    @BeforeEach
    void setUp() throws IOException {
        ai = AiJava.builder().storage(Storage.memory()).build();
        JsonCodec json = ai.jsonCodec();

        ghOutput = new AtomicReference<>(null);
        // Only "valid-token" resolves to a login -- every other token (including a manually-set
        // "bad-token" or the gh-detected one below) validates as an anonymous/invalid credential,
        // mirroring a real 401 from GitHub without ever hitting the network.
        fakeHttp = (url, token) -> "valid-token".equals(token)
                ? "{\"login\":\"octocat\"}"
                : "{\"message\":\"Bad credentials\"}";
        GithubAuth githubAuth = new GithubAuth(json, () -> null, ghOutput::get, fakeHttp);

        AccountAdmin admin = new AccountAdmin(new io.github.intisy.ai.shared.store.AccountStore(ai.store(), json), ai.clock());
        ManagementApi api = new ManagementApi(Collections::emptyList, admin, json,
                null, null, null, null, null, null, null, null, null, null, null, null,
                githubAuth, null);
        server = ExampleServer.start(0, api);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) server.stop();
        if (ai != null) ai.close();
    }

    @Test
    void statusStartsDisconnectedAndNeverExposesAToken() throws IOException {
        Response r = get("/api/github");
        assertEquals(200, r.status);
        assertTrue(r.body.contains("\"connected\":false"), r.body);
        assertTrue(r.body.contains("\"source\":\"none\""), r.body);
        assertFalse(r.body.contains("\"token\""), r.body);
    }

    @Test
    void postTokenConnectsAndNeverEchoesTheTokenBack() throws IOException {
        Response posted = post("/api/github/token", "{\"token\":\"valid-token\"}");
        assertEquals(200, posted.status, posted.body);
        assertFalse(posted.body.contains("valid-token"), "the token must never be echoed back: " + posted.body);
        assertTrue(posted.body.contains("\"source\":\"manual\""), posted.body);
        assertTrue(posted.body.contains("\"connected\":true"), posted.body);
        assertTrue(posted.body.contains("\"login\":\"octocat\""), posted.body);

        Response after = get("/api/github");
        assertEquals(200, after.status);
        assertTrue(after.body.contains("\"connected\":true"), after.body);
        assertTrue(after.body.contains("\"source\":\"manual\""), after.body);
        assertFalse(after.body.contains("valid-token"), after.body);
    }

    @Test
    void postTokenWithAnInvalidTokenStillConnectsTheManualTierButShowsNotConnected() throws IOException {
        Response posted = post("/api/github/token", "{\"token\":\"bad-token\"}");
        assertEquals(200, posted.status, posted.body);
        assertFalse(posted.body.contains("bad-token"), posted.body);
        assertTrue(posted.body.contains("\"source\":\"manual\""), posted.body);
        assertTrue(posted.body.contains("\"connected\":false"), posted.body);
    }

    @Test
    void deleteClearsTheManualTokenAndFallsBackToDisconnected() throws IOException {
        post("/api/github/token", "{\"token\":\"valid-token\"}");
        Response deleted = delete("/api/github");
        assertEquals(200, deleted.status);
        assertTrue(deleted.body.contains("\"source\":\"none\""), deleted.body);
        assertTrue(deleted.body.contains("\"connected\":false"), deleted.body);
        assertFalse(deleted.body.contains("valid-token"), deleted.body);
    }

    @Test
    void detectWithNoGhOutputReportsNotDetected() throws IOException {
        Response r = post("/api/github/detect");
        assertEquals(200, r.status);
        assertTrue(r.body.contains("\"detected\":false"), r.body);
        assertTrue(r.body.contains("\"source\":\"none\""), r.body);
    }

    @Test
    void detectPicksUpTheGhCliTokenAndNeverExposesIt() throws IOException {
        ghOutput.set("gh-cli-detected-token");
        Response r = post("/api/github/detect");
        assertEquals(200, r.status, r.body);
        assertTrue(r.body.contains("\"detected\":true"), r.body);
        assertTrue(r.body.contains("\"source\":\"gh\""), r.body);
        assertFalse(r.body.contains("gh-cli-detected-token"), r.body);
    }

    // -- tiny loopback HTTP client (test-only) --

    private Response get(String path) throws IOException {
        return read(open(path, "GET"));
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
        return read(open(path, "DELETE"));
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
