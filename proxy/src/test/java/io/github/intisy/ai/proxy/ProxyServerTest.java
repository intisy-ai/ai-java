package io.github.intisy.ai.proxy;

import io.github.intisy.ai.core.http.AiResponse;
import io.github.intisy.ai.core.routing.HandlerResolver;
import io.github.intisy.ai.core.routing.ProxyHandler;
import io.github.intisy.ai.core.routing.RoutingProfile;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for {@link ProxyServerImpl} over real sockets: binds on port 0, wires a
 * fake {@link HandlerResolver} ({@code rl} always rate-limited, {@code ok} always serves),
 * and drives the server with {@link HttpURLConnection}.
 */
class ProxyServerTest {

    private static final String CONFIG_FILE = "proxy-server-test.json";

    // TEST DATA ONLY — a real profile (e.g. Claude's) supplies its own tierOrder/nativeRateLimit.
    private static RoutingProfile testProfile() {
        RoutingProfile p = new RoutingProfile();
        p.configFile = CONFIG_FILE;
        p.routingKey = "providerRouting";
        p.tierSourceProvider = "ok";
        p.tierOrder = Collections.singletonList("opus");
        p.tierFallback = Collections.singletonList("opus");
        p.tierRegex = Pattern.compile("^claude-([a-z]+)-\\d");
        p.envPrefix = "ANTHROPIC";
        p.defaultContext = 200000;
        p.defaultOutput = 64000;
        p.nativeRateLimit = info -> {
            RoutingProfile.Synth s = new RoutingProfile.Synth();
            s.status = 429;
            s.headers = new HashMap<>();
            s.body = "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"rate limited\"}}";
            return s;
        };
        return p;
    }

    private static HandlerResolver fakeResolver() {
        Map<String, ProxyHandler> registry = new HashMap<>();
        registry.put("rl", (req, ctx) -> {
            Map<String, String> headers = new HashMap<>();
            headers.put("x-hub-rate-limited", "1");
            headers.put("x-hub-retry-after-ms", "1000");
            return new AiResponse(200, headers, new byte[0]);
        });
        registry.put("ok", (req, ctx) -> AiResponse.text(200, "served " + ctx.model));
        return HandlerResolvers.fromRegistry(registry);
    }

    private static Path tempConfigDir() throws IOException {
        Path dir = Files.createTempDirectory("ai-proxy-server");
        Files.createDirectories(dir.resolve("config"));
        return dir;
    }

    private static void writeModelMap(Path configDir, String json) throws IOException {
        Files.write(configDir.resolve("config").resolve(CONFIG_FILE), json.getBytes(StandardCharsets.UTF_8));
    }

    private static ProxyOptions baseOptions(Path configDir) {
        ProxyOptions opts = new ProxyOptions();
        opts.configDir = configDir.toString();
        opts.profile = testProfile();
        opts.resolveHandler = fakeResolver();
        opts.port = 0;
        opts.listProviders = () -> List.of("rl", "ok");
        return opts;
    }

    private static int get(String path) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(path).openConnection();
        conn.setRequestMethod("GET");
        return conn.getResponseCode();
    }

    private static String body(HttpURLConnection conn) throws IOException {
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static HttpURLConnection postJson(String url, String json) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("content-type", "application/json");
        try (OutputStream os = conn.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }
        return conn;
    }

    @Test
    void health_returnsOk() throws Exception {
        Path configDir = tempConfigDir();
        writeModelMap(configDir, "{\"modelMap\":{\"opus\":[{\"provider\":\"rl\",\"model\":\"m-rl\"},{\"provider\":\"ok\",\"model\":\"m-ok\"}]}}");
        ProxyServer server = ProxyServer.createProxyServer(baseOptions(configDir));
        int port = server.listen();
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/health").openConnection();
            conn.setRequestMethod("GET");
            assertEquals(200, conn.getResponseCode());
            assertEquals("ok", body(conn));
        } finally {
            server.close();
        }
    }

    @Test
    void ratelimitedPrimary_fallsBackToNextInChain() throws Exception {
        Path configDir = tempConfigDir();
        writeModelMap(configDir, "{\"modelMap\":{\"opus\":[{\"provider\":\"rl\",\"model\":\"m-rl\"},{\"provider\":\"ok\",\"model\":\"m-ok\"}]}}");
        ProxyServer server = ProxyServer.createProxyServer(baseOptions(configDir));
        int port = server.listen();
        try {
            HttpURLConnection conn = postJson("http://127.0.0.1:" + port + "/v1/messages", "{}");
            assertEquals(200, conn.getResponseCode());
            assertEquals("served m-ok", body(conn));
        } finally {
            server.close();
        }
    }

    @Test
    void allEntriesRateLimited_synthesizesNative429() throws Exception {
        Path configDir = tempConfigDir();
        writeModelMap(configDir, "{\"modelMap\":{\"opus\":[{\"provider\":\"rl\",\"model\":\"m-rl\"}]}}");
        ProxyServer server = ProxyServer.createProxyServer(baseOptions(configDir));
        int port = server.listen();
        try {
            HttpURLConnection conn = postJson("http://127.0.0.1:" + port + "/v1/messages", "{}");
            assertEquals(429, conn.getResponseCode());
            assertTrue(body(conn).contains("rate_limit_error"));
        } finally {
            server.close();
        }
    }
}
