package io.github.intisy.ai.proxy;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.intisy.ai.core.http.AiRequest;
import io.github.intisy.ai.core.http.AiResponse;
import io.github.intisy.ai.core.routing.Assignment;
import io.github.intisy.ai.core.routing.CatalogEntry;
import io.github.intisy.ai.core.routing.HandlerCtx;
import io.github.intisy.ai.core.routing.ProxyHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

/**
 * Java port of {@code libs/core-proxy/src/server.ts}'s {@code createProxyServer} over the
 * JDK {@link HttpServer}, replacing the node&lt;-&gt;web {@code createServer} adapter with an
 * {@link HttpExchange}&lt;-&gt;{@link AiRequest}/{@link AiResponse} adapter.
 */
final class ProxyServerImpl implements ProxyServer {

    private static final Gson GSON = new Gson();

    private final ProxyOptions opts;
    private final BiConsumer<String, String> notify;
    private HttpServer httpServer;
    private ExecutorService executor;

    ProxyServerImpl(ProxyOptions opts) {
        this.opts = opts;
        this.notify = opts.notify != null ? opts.notify : new Notify(opts.configDir, opts.log)::notify;
    }

    // -- lifecycle ----------------------------------------------------------

    @Override
    public int listen() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", opts.port), 0);
        httpServer.createContext("/", this::onExchange);
        executor = Executors.newCachedThreadPool();
        httpServer.setExecutor(executor);
        httpServer.start();
        return httpServer.getAddress().getPort();
    }

    @Override
    public void close() {
        if (httpServer != null) httpServer.stop(0);
        if (executor != null) executor.shutdown();
    }

    // -- HttpExchange <-> AiRequest/AiResponse adapter -----------------------

    private void onExchange(HttpExchange exchange) {
        try {
            try {
                AiResponse resp = adaptAndRoute(exchange);
                writeResponse(exchange, resp);
            } catch (Exception e) {
                writeErrorResponse(exchange, e);
            }
        } catch (IOException io) {
            // The exchange's socket is already broken; nothing more can be done.
        } finally {
            exchange.close();
        }
    }

    private AiResponse adaptAndRoute(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod() == null ? "GET" : exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        boolean skipBody = "GET".equals(method) || "HEAD".equals(method);
        byte[] bodyBytes = skipBody ? null : readAll(exchange.getRequestBody());

        Map<String, String> headers = new HashMap<>();
        for (Map.Entry<String, List<String>> h : exchange.getRequestHeaders().entrySet()) {
            List<String> vals = h.getValue();
            if (vals != null && !vals.isEmpty()) headers.put(h.getKey(), String.join(",", vals));
        }

        URI uri = exchange.getRequestURI();
        String rawPath = uri.getRawPath() != null ? uri.getRawPath() : "/";
        String rawQuery = uri.getRawQuery();
        String url = rawQuery != null ? rawPath + "?" + rawQuery : rawPath;

        AiRequest req = new AiRequest(method, url, headers, bodyBytes);
        return route(req, uri.getPath());
    }

    private void writeResponse(HttpExchange exchange, AiResponse resp) throws IOException {
        // undici's fetch (used by provider handlers) transparently decompresses the upstream
        // body but leaves content-encoding/content-length in place; forwarding those onto the
        // already-decoded body would make the client try to re-decompress plain bytes. Strip
        // both — the JDK server recomputes content-length from what we actually write.
        if (resp.headers != null) {
            for (Map.Entry<String, String> e : resp.headers.entrySet()) {
                String key = e.getKey();
                if (key == null) continue;
                String lower = key.toLowerCase(Locale.ROOT);
                if ("content-encoding".equals(lower) || "content-length".equals(lower)) continue;
                exchange.getResponseHeaders().set(key, e.getValue());
            }
        }
        byte[] body = resp.body != null ? resp.body : new byte[0];
        if (body.length == 0) {
            exchange.sendResponseHeaders(resp.status, -1);
        } else {
            exchange.sendResponseHeaders(resp.status, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        }
    }

    private void writeErrorResponse(HttpExchange exchange, Exception e) throws IOException {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("message", String.valueOf(e.getMessage() != null ? e.getMessage() : e));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "error");
        body.put("error", err);
        byte[] json = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", "application/json");
        exchange.sendResponseHeaders(502, json.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(json);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }

    // -- routing --------------------------------------------------------------

    private AiResponse route(AiRequest req, String path) {
        if ("/health".equals(path)) return AiResponse.text(200, "ok");
        if ("/v1/models".equals(path) || path.startsWith("/v1/models/")) return modelsResponse(path);

        List<Assignment> chain = resolveAssignment(req);
        if (chain.isEmpty()) {
            return errorResponse(503, "No provider/model assigned for this tier. Run cc auth -> Providers.");
        }

        // The user must SEE substitutions: a healed primary means the stored mapping no
        // longer matched the catalog and routing re-derived it.
        Assignment primary = chain.get(0);
        if (primary.derived) {
            notify.accept(
                    "Model mapping healed: serving " + primary.provider + " · " + displayName(primary)
                            + " (the stored model for this tier is no longer in the catalog).",
                    "info");
        }

        // Try the tier's models in order; advance to the next only when one is rate-limited,
        // so a chain stops only once EVERY model in it is exhausted.
        AiResponse lastResp = null;
        long resetMs = 0;
        for (int i = 0; i < chain.size(); i++) {
            Assignment assigned = chain.get(i);
            ProxyHandler handler;
            try {
                handler = opts.resolveHandler.resolve(assigned.provider);
            } catch (Exception e) {
                opts.log.accept("handler load failed for " + assigned.provider + ": " + e.getMessage());
                handler = null;
            }
            if (handler == null) {
                lastResp = errorResponse(503, "Provider '" + assigned.provider + "' has no proxy handler installed.");
                continue;
            }
            AiResponse resp;
            try {
                resp = handler.handle(req, new HandlerCtx(opts.configDir, opts.log, assigned.model));
            } catch (Exception e) {
                opts.log.accept("handler error for " + assigned.provider + ": " + e.getMessage());
                lastResp = errorResponse(502, "Provider handler failed: " + e.getMessage());
                continue;
            }
            lastResp = resp;
            if (RateLimit.isRateLimited(resp)) {
                long ms = RateLimit.rateLimitResetMs(resp, System.currentTimeMillis());
                if (ms > resetMs) resetMs = ms;
                opts.log.accept("rate-limited on " + assigned.provider + "/" + assigned.model + " — trying next fallback");
                continue;
            }
            // Never switch the user silently: announce when a fallback (not the primary) served.
            if (i > 0) {
                notify.accept(displayName(primary) + " rate-limited → served by " + displayName(assigned), null);
            }
            return resp; // success or a non-rate-limit error — surface it
        }

        // Every model in the chain was rate-limited (or unavailable) — hand back a native
        // 429 so the client renders its own rate-limit UI, consistent across providers.
        if ((lastResp != null && lastResp.status == 429) || resetMs > System.currentTimeMillis()) {
            notify.accept("All mapped models for this tier are rate-limited — request rejected with the earliest reset time.", null);
            return RateLimit.rateLimitFinal(lastResp, resetMs, opts.profile);
        }
        return lastResp != null ? lastResp : errorResponse(503, "No provider handler available for this tier.");
    }

    private static String displayName(Assignment a) {
        return a.name != null && !a.name.isEmpty() ? a.name : a.model;
    }

    // The ORDERED CHAIN [{provider, model}, ...] assigned to the request's tier (primary +
    // fallbacks). Healed: stale/unset tiers auto-derive to the current catalog, so routing
    // tracks a model refresh even if never re-assigned.
    private List<Assignment> resolveAssignment(AiRequest req) {
        String requested = requestedModel(req);
        Map<String, List<Assignment>> map = ModelMap.resolveModelMap(opts.configDir, opts.profile);

        // Exact-id match first: the wrapper injects each tier's primary model id as an env
        // var, so the request model can be a backend id carrying no tier keyword — recover
        // its tier by matching the assigned ids before keyword classification.
        if (!requested.isEmpty()) {
            for (List<Assignment> chain : map.values()) {
                for (Assignment a : chain) {
                    if (requested.equals(a.model)) return chain;
                }
            }
        }

        String slot = slotForModel(requested, map);
        if ("default".equals(slot) && !requested.isEmpty()) {
            // A model picked DIRECTLY (e.g. via /model) that isn't in any tier chain must be
            // served as itself when a provider offers it — falling through to the default
            // tier would silently substitute a different model.
            List<CatalogEntry> catalog = ModelMap.catalogEntries(opts.configDir, opts.listProviders.get());
            CatalogEntry found = null;
            for (CatalogEntry e : catalog) {
                if (requested.equals(e.model) && !e.model.endsWith("-auto")) {
                    found = e;
                    break;
                }
            }
            if (found != null) {
                List<Assignment> single = new ArrayList<>();
                single.add(new Assignment(found.provider, found.model, found.name, false));
                return single;
            }
            boolean matchesNative = opts.profile.nativeModelPattern != null
                    && opts.profile.nativeModelPattern.matcher(requested).find();
            if (!matchesNative) {
                notify.accept("Requested model '" + requested + "' is not in any provider catalog — serving the Default tier instead.", null);
            }
        }

        List<Assignment> chain = map.get(slot);
        if (chain != null && !chain.isEmpty()) return chain;
        List<Assignment> dflt = map.get("default");
        return dflt != null ? dflt : new ArrayList<>();
    }

    // Classify a requested model into a mapping slot by tier keyword. Slots come from the
    // resolved map (detected families incl. new ones) — nothing hardcoded here.
    private static String slotForModel(String model, Map<String, List<Assignment>> map) {
        String m = model == null ? "" : model.toLowerCase(Locale.ROOT);
        for (String slot : map.keySet()) {
            if (!"default".equals(slot) && m.contains(slot)) return slot;
        }
        return "default";
    }

    private static String requestedModel(AiRequest req) {
        if (req.body == null || req.body.length == 0) return "";
        try {
            JsonElement el = JsonParser.parseString(new String(req.body, StandardCharsets.UTF_8));
            if (el != null && el.isJsonObject()) {
                JsonElement m = el.getAsJsonObject().get("model");
                if (m != null && m.isJsonPrimitive() && m.getAsJsonPrimitive().isString()) {
                    return m.getAsString();
                }
            }
        } catch (Exception ignored) {
            // malformed/non-JSON body — treat as no requested model, mirrors the JS try/catch
        }
        return "";
    }

    // -- /v1/models catalog ---------------------------------------------------

    // Claude Code (and similarly-shaped clients) validate their custom default-model ids
    // against /v1/models. Provider-mapped ids don't exist upstream, so forwarding a 404
    // would show the model picker stuck loading. Serve the loader's own catalog instead —
    // every mapped id resolves.
    private AiResponse modelsResponse(String path) {
        List<CatalogEntry> raw = ModelMap.catalogEntries(opts.configDir, opts.listProviders.get());
        List<CatalogEntry> entries = new ArrayList<>();
        for (CatalogEntry e : raw) {
            if (!e.model.endsWith("-auto")) entries.add(e);
        }

        String id = decode(path.replaceFirst("^/v1/models/?", ""));
        if (!id.isEmpty()) {
            for (CatalogEntry e : entries) {
                if (e.model.equals(id)) return jsonResponse(200, modelInfo(e));
            }
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("type", "not_found_error");
            err.put("message", "model not found: " + id);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("type", "error");
            body.put("error", err);
            return jsonResponse(404, body);
        }

        Set<String> seen = new HashSet<>();
        List<Map<String, Object>> data = new ArrayList<>();
        for (CatalogEntry e : entries) {
            if (seen.contains(e.model)) continue; // same id may exist under several providers
            seen.add(e.model);
            data.add(modelInfo(e));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        body.put("first_id", data.isEmpty() ? null : data.get(0).get("id"));
        body.put("last_id", data.isEmpty() ? null : data.get(data.size() - 1).get("id"));
        body.put("has_more", false);
        return jsonResponse(200, body);
    }

    private Map<String, Object> modelInfo(CatalogEntry entry) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "model");
        m.put("id", entry.model);
        m.put("display_name", entry.name != null && !entry.name.isEmpty() ? entry.name : entry.model);
        m.put("created_at", "2025-01-01T00:00:00Z");
        m.put("max_input_tokens", entry.contextLimit != null ? entry.contextLimit : opts.profile.defaultContext);
        m.put("max_tokens", entry.outputLimit != null ? entry.outputLimit : opts.profile.defaultOutput);
        return m;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    // -- shared response builders -----------------------------------------------

    private static AiResponse errorResponse(int status, String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("type", "loader_proxy_error");
        err.put("message", message);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "error");
        body.put("error", err);
        return jsonResponse(status, body);
    }

    private static AiResponse jsonResponse(int status, Object body) {
        return AiResponse.json(status, GSON.toJson(body));
    }
}
