package io.github.intisy.ai.exampleserver;

import io.github.intisy.ai.jvm.AiJava;
import io.github.intisy.ai.jvm.Storage;
import io.github.intisy.ai.shared.routing.RoutingProfile;
import io.github.intisy.ai.shared.spi.Store;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExampleServerIntegrationTest {

    private static final String CONFIG_FILE = "example-server-routing.json";

    private AiJava ai;
    private ExampleServer server;

    @BeforeEach
    void setUp() {
        String providersDir = System.getProperty("exampleserver.providersDir");
        assertTrue(providersDir != null && !providersDir.isEmpty(),
                "exampleserver.providersDir must be set by the Gradle test task");
        ai = AiJava.builder().storage(Storage.memory()).providersDir(Paths.get(providersDir)).build();
        Store store = ai.store();
        ServerSeeds.seedEcho(store, ai.jsonCodec(), CONFIG_FILE);
        RoutingProfile profile = ServerProfile.echoTiers(CONFIG_FILE);
        server = ExampleServer.start(ai, profile, 0); // ephemeral port
    }

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) server.stop();
        if (ai != null) ai.close();
    }

    @Test
    void postMessagesRoutesToEchoProvider() throws IOException {
        String body = "{\"model\":\"claude-haiku-4\",\"messages\":[]}";
        Response r = post("/v1/messages", body);
        assertEquals(200, r.status);
        assertTrue(r.body.contains("Echo provider handled your request"), r.body);
        assertTrue(r.body.contains("m-echo-haiku"), r.body);
    }

    @Test
    void getModelsReturnsCatalog() throws IOException {
        Response r = get("/v1/models");
        assertEquals(200, r.status);
        assertTrue(r.body.contains("m-echo-haiku"), r.body);
    }

    @Test
    void healthzIsLiveWithoutRouting() throws IOException {
        Response r = get("/healthz");
        assertEquals(200, r.status);
        assertTrue(r.body.contains("ok"), r.body);
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
