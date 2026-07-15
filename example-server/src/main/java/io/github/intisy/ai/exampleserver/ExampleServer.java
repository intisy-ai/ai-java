package io.github.intisy.ai.exampleserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.intisy.ai.exampleserver.api.ManagementApi;
import io.github.intisy.ai.exampleserver.web.Dashboard;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A thin transport adapter: converts each {@link HttpExchange} into the shared {@link HttpRequest},
 * hands it to a pre-wired {@link AiJava.WiredRouter}, and writes the buffered {@link HttpResponse}
 * back. The Router already dispatches {@code /v1/messages} and {@code /v1/models} internally.
 *
 * <p>Four contexts are registered: {@code /healthz} (liveness, answered here without touching the
 * router), {@code /api} (the optional {@link ManagementApi}), {@code /v1} (forwarded to the router
 * — covers {@code /v1/messages} and {@code /v1/models}), and {@code /} (the self-contained
 * {@link Dashboard} HTML page). {@code com.sun.net.httpserver} dispatches on the longest matching
 * registered prefix, so a request under {@code /v1/...} or {@code /api/...} is routed to that
 * context and never reaches the {@code /} handler — the dashboard and the API/router coexist
 * without either shadowing the other. The shared request/response bodies are plain strings — the
 * engine is fully buffered, so there is no SSE streaming to pass through.
 */
public final class ExampleServer {

    private final HttpServer http;
    private final int port;
    private final ExecutorService executor;

    private ExampleServer(HttpServer http, int port, ExecutorService executor) {
        this.http = http;
        this.port = port;
        this.executor = executor;
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
        return start(ai.router(profile), port, api);
    }

    /**
     * Serves with a pre-built {@code router} instead of deriving one from {@code ai.router(profile)}
     * — the seam a caller uses to wire a router backed by something other than {@code ai}'s own
     * (fixed-at-build-time) {@link io.github.intisy.ai.jvm.provider.ProviderRegistry}, e.g. one
     * whose {@link io.github.intisy.ai.shared.routing.HandlerResolver} reads through a swappable
     * {@link io.github.intisy.ai.exampleserver.discovery.ProviderRegistryHolder} so newly installed
     * providers become routable without a restart. All other behavior (the four contexts) is
     * identical to the other {@code start} overloads. Pass {@code null} for {@code api} to skip the
     * management context.
     */
    public static ExampleServer start(AiJava.WiredRouter router, int port, HttpHandler api) {
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
        http.createContext("/v1", exchange -> handleRouted(exchange, router));
        http.createContext("/", new Dashboard());
        ExecutorService executor = Executors.newFixedThreadPool(8);
        http.setExecutor(executor); // small pool: a slow request (e.g. a provider install) can't block every other endpoint
        http.start();
        return new ExampleServer(http, http.getAddress().getPort(), executor);
    }

    public int port() {
        return port;
    }

    public void stop() {
        http.stop(0);
        executor.shutdownNow();
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
