package io.github.intisy.ai.exampleserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.intisy.ai.exampleserver.api.ManagementApi;
import io.github.intisy.ai.exampleserver.web.Dashboard;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The built-in management + dashboard server: converts each {@link HttpExchange} for
 * {@code /healthz}/{@code /api}/{@code /} and answers it directly. It carries NO routing engine
 * and NO {@code /v1} context -- the console (dashboard) runs in the SAME JVM as the installed
 * providers, so it reaches them by a DIRECT Java call (see {@code MessagesAdmin}/{@code
 * ConfigAdmin}/{@code QuotaAdmin}, all resolved through the shared {@code ProviderRegistryHolder}),
 * never through a router. Routing-over-HTTP for OUT-OF-PROCESS apps (Claude Code, OpenCode) is the
 * per-proxy {@link ProxyServer}'s job -- each installed proxy runs its own {@code ProxyServer} on
 * its own port and serves {@code /v1/messages}/{@code /v1/models} there.
 *
 * <p>Three contexts are registered: {@code /healthz} (liveness), {@code /api} (the {@link
 * ManagementApi}), and {@code /} (the self-contained {@link Dashboard} HTML page). {@code
 * com.sun.net.httpserver} dispatches on the longest matching registered prefix, so a request under
 * {@code /api/...} is routed to that context and never reaches the {@code /} handler.
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
     * Binds {@code 127.0.0.1:port} ({@code port == 0} picks an ephemeral port) and starts serving
     * {@code /healthz}, {@code /api} (routed to {@code api}), and {@code /} (the dashboard). Pass
     * {@code null} for {@code api} to skip the management context entirely.
     */
    public static ExampleServer start(int port, HttpHandler api) {
        HttpServer http;
        try {
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        } catch (IOException e) {
            throw new RuntimeException("failed to bind example server", e);
        }
        http.createContext("/healthz", exchange -> respond(exchange, 200, "ok", "text/plain"));
        if (api != null) {
            http.createContext("/api", api);
        }
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
