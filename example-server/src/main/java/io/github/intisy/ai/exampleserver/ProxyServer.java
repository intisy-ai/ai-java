package io.github.intisy.ai.exampleserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.intisy.ai.jvm.AiJava;
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
 * A routing-only HTTP listener: converts each {@link HttpExchange} into the shared
 * {@link HttpRequest}, hands it to a pre-wired {@link AiJava.WiredRouter}, and writes the buffered
 * {@link HttpResponse} back. Unlike {@link ExampleServer} it registers ONLY {@code /healthz} and
 * {@code /v1} — it deliberately does NOT serve the dashboard ({@code /}) or the management API
 * ({@code /api}), because a proxy port must never expose the management console.
 */
public final class ProxyServer {
    private final HttpServer http;
    private final int port;
    private final ExecutorService executor;

    private ProxyServer(HttpServer http, int port, ExecutorService executor) {
        this.http = http;
        this.port = port;
        this.executor = executor;
    }

    public static ProxyServer start(AiJava.WiredRouter router, int port) {
        HttpServer http;
        try {
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        } catch (IOException e) {
            throw new RuntimeException("failed to bind proxy server on port " + port, e);
        }
        http.createContext("/healthz", exchange -> {
            if ("/healthz".equals(exchange.getRequestURI().getPath())) {
                respond(exchange, 200, "ok", "text/plain");
            } else {
                handleRouted(exchange, router);
            }
        });
        http.createContext("/v1", exchange -> handleRouted(exchange, router));
        // NOTE: no "/" and no "/api" — routing-only by design.
        ExecutorService executor = Executors.newFixedThreadPool(8);
        http.setExecutor(executor);
        http.start();
        return new ProxyServer(http, http.getAddress().getPort(), executor);
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
        req.url = exchange.getRequestURI().toString();
        req.headers = flattenHeaders(exchange);
        req.body = readBody(exchange.getRequestBody());
        HttpResponse resp;
        try {
            resp = router.route(req);
        } catch (RuntimeException e) {
            respond(exchange, 500, "{\"type\":\"error\",\"error\":{\"type\":\"server_error\",\"message\":\"routing failed\"}}",
                    "application/json");
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
        while ((n = in.read(chunk)) != -1) buf.write(chunk, 0, n);
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

    private static void respond(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
