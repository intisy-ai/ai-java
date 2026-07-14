package io.github.intisy.ai.examples.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A minimal in-JVM OAuth token endpoint, the ONE true external edge the account-manager demo/tests
 * fake. It speaks just enough of the {@code grant_type=refresh_token} protocol for
 * {@code TokenRefresh}: a POST whose form body carries a known {@code refresh_token} gets a fresh
 * {@code access_token} back; a {@code refresh_token} listed as revoked gets a {@code 400} with
 * {@code error=invalid_grant} (which the account manager treats as "disable this account").
 *
 * <p>Everything downstream of this — the {@code HttpClient}, the account store, the manager — is the
 * REAL component, so the demo/test exercises the true refresh round trip end to end.
 */
public final class FakeTokenServer implements Closeable {

    private final HttpServer server;
    private final String tokenUrl;
    private final String issuedAccessToken;
    private final String issuedRefreshToken;
    private final long expiresInSeconds;
    private final String revokedRefreshToken;
    private int refreshRequestCount;

    private FakeTokenServer(HttpServer server, String tokenUrl, String issuedAccessToken,
                            String issuedRefreshToken, long expiresInSeconds, String revokedRefreshToken) {
        this.server = server;
        this.tokenUrl = tokenUrl;
        this.issuedAccessToken = issuedAccessToken;
        this.issuedRefreshToken = issuedRefreshToken;
        this.expiresInSeconds = expiresInSeconds;
        this.revokedRefreshToken = revokedRefreshToken;
    }

    /**
     * Starts a server on an ephemeral port. {@code revokedRefreshToken} refresh attempts get an
     * {@code invalid_grant} 400; any other refresh token gets {@code issuedAccessToken} back.
     */
    public static FakeTokenServer start(String issuedAccessToken, String issuedRefreshToken,
                                        long expiresInSeconds, String revokedRefreshToken) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String tokenUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/token";
        FakeTokenServer instance = new FakeTokenServer(server, tokenUrl, issuedAccessToken,
                issuedRefreshToken, expiresInSeconds, revokedRefreshToken);
        server.createContext("/token", instance::handleToken);
        server.setExecutor(null); // default single-threaded executor is plenty for a demo/test
        server.start();
        return instance;
    }

    private void handleToken(HttpExchange exchange) throws IOException {
        refreshRequestCount++;
        Map<String, String> form = parseForm(readBody(exchange.getRequestBody()));
        String refreshToken = form.get("refresh_token");

        if (revokedRefreshToken != null && revokedRefreshToken.equals(refreshToken)) {
            respond(exchange, 400, "{\"error\":\"invalid_grant\",\"error_description\":\"refresh token revoked\"}");
            return;
        }
        String body = "{"
                + "\"access_token\":\"" + issuedAccessToken + "\","
                + "\"refresh_token\":\"" + issuedRefreshToken + "\","
                + "\"expires_in\":" + expiresInSeconds
                + "}";
        respond(exchange, 200, body);
    }

    public String tokenUrl() {
        return tokenUrl;
    }

    /** How many refresh POSTs the endpoint received — lets a test assert the real network call happened. */
    public int refreshRequestCount() {
        return refreshRequestCount;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("content-type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String readBody(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[1024];
        int n;
        while ((n = in.read(chunk)) != -1) buffer.write(chunk, 0, n);
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> form = new LinkedHashMap<>();
        if (body == null || body.isEmpty()) return form;
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            form.put(urlDecode(pair.substring(0, eq)), urlDecode(pair.substring(eq + 1)));
        }
        return form;
    }

    private static String urlDecode(String value) {
        try {
            return java.net.URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }
}
