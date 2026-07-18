package io.github.intisy.ai.exampleserver.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Serves the self-contained dashboard page (inline CSS + JS, no external assets) at {@code GET /}
 * from the classpath resource {@code /dashboard/index.html}. Registered at the {@code /} context;
 * because {@code com.sun.net.httpserver} matches the longest registered prefix, the {@code /api}
 * context wins over this one and is never seen here. Anything else under {@code /} that isn't the
 * exact root path is a 404 — this handler owns no other routes (there is no {@code /v1} context on
 * this server at all; see {@link io.github.intisy.ai.exampleserver.ExampleServer}'s javadoc).
 */
public final class Dashboard implements HttpHandler {

    private static final String RESOURCE_PATH = "/dashboard/index.html";

    private final byte[] html;

    public Dashboard() {
        this.html = loadResource();
    }

    private static byte[] loadResource() {
        try (InputStream in = Dashboard.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource " + RESOURCE_PATH);
            }
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) != -1) {
                buf.write(chunk, 0, n);
            }
            return buf.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read " + RESOURCE_PATH, e);
        }
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ("GET".equals(exchange.getRequestMethod()) && "/".equals(path)) {
            exchange.getResponseHeaders().set("content-type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, html.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(html);
            }
            return;
        }
        byte[] body = "not found".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(404, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }
}
