package io.github.intisy.ai.exampleserver.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.github.intisy.ai.exampleserver.admin.AccountAdmin;
import io.github.intisy.ai.shared.spi.JsonCodec;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Small hand-rolled JSON API over {@link AccountAdmin} + the discovered provider ids, registered
 * at the {@code /api} context. Routes by exact path + method only (no path templating library):
 *
 * <pre>
 * GET    /api/providers                                 -> [{"id":..,"accounts":&lt;int&gt;}]
 * GET    /api/providers/{id}/accounts                    -> [AccountView...]
 * POST   /api/providers/{id}/accounts/{accId}/enable     -> 204
 * POST   /api/providers/{id}/accounts/{accId}/disable    -> 204
 * DELETE /api/providers/{id}/accounts/{accId}            -> 204
 * (anything else under /api/)                            -> 404 {"error":"not found"}
 * </pre>
 *
 * Path segments ({@code id}/{@code accId}) are URL-decoded exactly once. The whole handler body
 * is wrapped so an unexpected exception never leaks a stack trace to the client — it degrades to
 * a plain 500 JSON error instead.
 */
public final class ManagementApi implements HttpHandler {

    private final Supplier<List<String>> providerIds;
    private final AccountAdmin admin;
    private final JsonCodec json;

    public ManagementApi(Supplier<List<String>> providerIds, AccountAdmin admin, JsonCodec json) {
        this.providerIds = providerIds;
        this.admin = admin;
        this.json = json;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            route(exchange);
        } catch (RuntimeException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "internal server error");
            respondJson(exchange, 500, body);
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String[] seg = segments(exchange.getRequestURI().getPath());

        if ("GET".equals(method) && seg.length == 2
                && "api".equals(seg[0]) && "providers".equals(seg[1])) {
            handleListProviders(exchange);
            return;
        }
        if ("GET".equals(method) && seg.length == 4
                && "api".equals(seg[0]) && "providers".equals(seg[1]) && "accounts".equals(seg[3])) {
            handleListAccounts(exchange, decode(seg[2]));
            return;
        }
        if ("POST".equals(method) && seg.length == 6
                && "api".equals(seg[0]) && "providers".equals(seg[1]) && "accounts".equals(seg[3])
                && ("enable".equals(seg[5]) || "disable".equals(seg[5]))) {
            handleSetEnabled(exchange, decode(seg[2]), decode(seg[4]), "enable".equals(seg[5]));
            return;
        }
        if ("DELETE".equals(method) && seg.length == 5
                && "api".equals(seg[0]) && "providers".equals(seg[1]) && "accounts".equals(seg[3])) {
            handleRemove(exchange, decode(seg[2]), decode(seg[4]));
            return;
        }
        handleNotFound(exchange);
    }

    private void handleListProviders(HttpExchange exchange) throws IOException {
        List<String> ids = providerIds.get();
        List<Map<String, Object>> body = new ArrayList<>();
        if (ids != null) {
            for (String id : ids) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", id);
                entry.put("accounts", admin.list(id).size());
                body.add(entry);
            }
        }
        respondJson(exchange, 200, body);
    }

    private void handleListAccounts(HttpExchange exchange, String providerId) throws IOException {
        respondJson(exchange, 200, admin.list(providerId));
    }

    private void handleSetEnabled(HttpExchange exchange, String providerId, String accountId, boolean enabled)
            throws IOException {
        admin.setEnabled(providerId, accountId, enabled);
        respondNoBody(exchange, 204);
    }

    private void handleRemove(HttpExchange exchange, String providerId, String accountId) throws IOException {
        admin.remove(providerId, accountId);
        respondNoBody(exchange, 204);
    }

    private void handleNotFound(HttpExchange exchange) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "not found");
        respondJson(exchange, 404, body);
    }

    /** Path split into non-empty segments, e.g. {@code /api/providers} -> {@code ["api","providers"]}. */
    private static String[] segments(String path) {
        String[] raw = path.split("/", -1);
        if (raw.length > 0 && raw[0].isEmpty()) {
            return Arrays.copyOfRange(raw, 1, raw.length);
        }
        return raw;
    }

    private static String decode(String segment) {
        try {
            return URLDecoder.decode(segment, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return segment; // UTF-8 is always supported; unreachable in practice
        }
    }

    private void respondJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = json.stringify(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void respondNoBody(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }
}
