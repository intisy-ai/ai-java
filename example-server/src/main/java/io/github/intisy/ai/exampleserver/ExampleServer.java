package io.github.intisy.ai.exampleserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.intisy.ai.exampleserver.api.ManagementApi;
import io.github.intisy.ai.jvm.AiJava;
import io.github.intisy.ai.shared.routing.RoutingProfile;
import io.github.intisy.ai.shared.spi.http.HttpRequest;
import io.github.intisy.ai.shared.spi.http.HttpResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A thin transport adapter: converts each {@link HttpExchange} into the shared {@link HttpRequest},
 * hands it to a pre-wired {@link AiJava.WiredRouter}, and writes the buffered {@link HttpResponse}
 * back. The Router already dispatches {@code /v1/models} and its own {@code /health} internally, so
 * this server just forwards every non-liveness path to it; {@code /healthz} is answered here
 * without touching the router. The shared request/response bodies are plain strings — the engine is
 * fully buffered, so there is no SSE streaming to pass through.
 */
public final class ExampleServer {

    private final HttpServer http;
    private final int port;

    private ExampleServer(HttpServer http, int port) {
        this.http = http;
        this.port = port;
    }

    /**
     * Binds {@code 127.0.0.1:port} ({@code port == 0} picks an ephemeral port) and starts serving.
     * All requests route through {@code ai.router(profile)}, except {@code GET /healthz}.
     */
    public static ExampleServer start(AiJava ai, RoutingProfile profile, int port) {
        return start(ai, profile, port, null);
    }

    /**
     * Same as {@link #start(AiJava, RoutingProfile, int)}, additionally registering {@code api}
     * (typically a {@link ManagementApi}) at the {@code /api} context, alongside {@code /healthz}
     * and the router-handled paths. Pass {@code null} for {@code api} to skip the management
     * context entirely (that's what the two-arg overload does).
     */
    public static ExampleServer start(AiJava ai, RoutingProfile profile, int port, HttpHandler api) {
        AiJava.WiredRouter router = ai.router(profile);
        HttpServer http;
        try {
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        } catch (IOException e) {
            throw new RuntimeException("failed to bind example server", e);
        }
        http.createContext("/healthz", exchange -> {
            if ("/healthz".equals(exchange.getRequestURI().getPath())) {
                respond(exchange, 200, "ok", "text/plain");
            } else {
                handleRouted(exchange, router);
            }
        });
        if (api != null) {
            http.createContext("/api", api);
        }
        http.createContext("/", exchange -> handleRouted(exchange, router));
        http.setExecutor(null); // default executor: a single background thread
        http.start();
        return new ExampleServer(http, http.getAddress().getPort());
    }

    public int port() {
        return port;
    }

    public void stop() {
        http.stop(0);
    }

    private static void handleRouted(HttpExchange exchange, AiJava.WiredRouter router) throws IOException {
        HttpRequest req = new HttpRequest();
        req.method = exchange.getRequestMethod();
        req.url = exchange.getRequestURI().toString(); // path + query, which Router.pathOf splits
        req.headers = flattenHeaders(exchange);
        req.body = readBody(exchange.getRequestBody());

        HttpResponse resp;
        try {
            resp = router.route(req);
        } catch (RuntimeException e) {
            respond(exchange, 500, "{\"type\":\"error\",\"error\":{\"type\":\"server_error\","
                    + "\"message\":\"routing failed\"}}", "application/json");
            return;
        }
        writeResponse(exchange, resp);
    }

    private static Map<String, String> flattenHeaders(HttpExchange exchange) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : exchange.getRequestHeaders().entrySet()) {
            List<String> values = e.getValue();
            if (e.getKey() != null && values != null && !values.isEmpty()) {
                headers.put(e.getKey().toLowerCase(), values.get(0));
            }
        }
        return headers;
    }

    private static String readBody(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) != -1) {
            buf.write(chunk, 0, n);
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void writeResponse(HttpExchange exchange, HttpResponse resp) throws IOException {
        if (resp.headers != null) {
            for (Map.Entry<String, String> e : resp.headers.entrySet()) {
                if (e.getKey() != null && e.getValue() != null
                        && !"content-length".equalsIgnoreCase(e.getKey())) {
                    exchange.getResponseHeaders().set(e.getKey(), e.getValue());
                }
            }
        }
        byte[] body = (resp.body != null ? resp.body : "").getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(resp.status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body, String contentType)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
