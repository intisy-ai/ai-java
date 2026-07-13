package io.github.intisy.ai.shared.logic;

import io.github.intisy.ai.shared.routing.Assignment;
import io.github.intisy.ai.shared.routing.CatalogEntry;
import io.github.intisy.ai.shared.routing.HandlerCtx;
import io.github.intisy.ai.shared.routing.ProxyHandler;
import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.http.HttpRequest;
import io.github.intisy.ai.shared.spi.http.HttpResponse;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure routing engine: resolves a single {@link HttpRequest} to an {@link HttpResponse} by
 * walking the tier's {provider, model} fallback chain, with no transport/socket concerns —
 * the caller (a JVM daemon, a TeaVM/browser host, or a test) owns the actual HTTP plumbing.
 *
 * <p>Java port of the routing half of {@code libs/core-proxy/src/server.ts} /
 * the old JVM {@code ProxyServerImpl.route}, minus the {@code HttpServer}/{@code HttpExchange}
 * adapter (now the caller's problem, not this engine's).
 */
public final class Router {

    private Router() {
    }

    // -- routing --------------------------------------------------------------

    public static HttpResponse route(HttpRequest req, RouterOptions opts) {
        String path = pathOf(req.url);
        if ("/health".equals(path)) return textResponse(200, "ok");
        if ("/v1/models".equals(path) || path.startsWith("/v1/models/")) return modelsResponse(path, opts);

        List<Assignment> chain = resolveAssignment(req, opts);
        if (chain.isEmpty()) {
            return errorResponse(503, "No provider/model assigned for this tier. Run cc auth -> Providers.", opts.json);
        }

        // The user must SEE substitutions: a healed primary means the stored mapping no
        // longer matched the catalog and routing re-derived it.
        Assignment primary = chain.get(0);
        if (primary.derived) {
            notify(opts, "Model mapping healed: serving " + primary.provider + " · " + displayName(primary)
                    + " (the stored model for this tier is no longer in the catalog).", "info");
        }

        // Try the tier's models in order; advance to the next only when one is rate-limited,
        // so a chain stops only once EVERY model in it is exhausted.
        HttpResponse lastResp = null;
        long resetMs = 0;
        for (int i = 0; i < chain.size(); i++) {
            Assignment assigned = chain.get(i);
            ProxyHandler handler;
            try {
                handler = opts.resolveHandler.resolve(assigned.provider);
            } catch (Exception e) {
                log(opts, "handler load failed for " + assigned.provider + ": " + e.getMessage());
                handler = null;
            }
            if (handler == null) {
                lastResp = errorResponse(503, "Provider '" + assigned.provider + "' has no proxy handler installed.", opts.json);
                continue;
            }
            HttpResponse resp;
            try {
                resp = handler.handle(req, new HandlerCtx(opts.configDir, opts.log, assigned.model));
            } catch (Exception e) {
                log(opts, "handler error for " + assigned.provider + ": " + e.getMessage());
                lastResp = errorResponse(502, "Provider handler failed: " + e.getMessage(), opts.json);
                continue;
            }
            lastResp = resp;
            if (RateLimit.isRateLimited(resp)) {
                long ms = RateLimit.rateLimitResetMs(resp, opts.clock.now());
                if (ms > resetMs) resetMs = ms;
                log(opts, "rate-limited on " + assigned.provider + "/" + assigned.model + " — trying next fallback");
                continue;
            }
            // Never switch the user silently: announce when a fallback (not the primary) served.
            if (i > 0) {
                notify(opts, displayName(primary) + " rate-limited → served by " + displayName(assigned), null);
            }
            return resp; // success or a non-rate-limit error — surface it
        }

        // Every model in the chain was rate-limited (or unavailable) — hand back a native
        // 429 so the client renders its own rate-limit UI, consistent across providers.
        if ((lastResp != null && lastResp.status == 429) || resetMs > opts.clock.now()) {
            notify(opts, "All mapped models for this tier are rate-limited — request rejected with the earliest reset time.", null);
            return RateLimit.rateLimitFinal(lastResp, resetMs, opts.profile);
        }
        return lastResp != null ? lastResp : errorResponse(503, "No provider handler available for this tier.", opts.json);
    }

    private static void notify(RouterOptions opts, String message, String level) {
        if (opts.notify != null) opts.notify.notify(message, level);
    }

    private static void log(RouterOptions opts, String message) {
        if (opts.log != null) opts.log.log(message);
    }

    private static String displayName(Assignment a) {
        return a.name != null && !a.name.isEmpty() ? a.name : a.model;
    }

    // The ORDERED CHAIN [{provider, model}, ...] assigned to the request's tier (primary +
    // fallbacks). Healed: stale/unset tiers auto-derive to the current catalog, so routing
    // tracks a model refresh even if never re-assigned.
    private static List<Assignment> resolveAssignment(HttpRequest req, RouterOptions opts) {
        String requested = requestedModel(req, opts.json);
        Map<String, List<Assignment>> map = ModelMap.resolveModelMap(opts.store, opts.json, opts.profile);

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
            List<CatalogEntry> catalog = ModelMap.catalogEntries(opts.store, opts.json, opts.listProviders.get());
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
                notify(opts, "Requested model '" + requested + "' is not in any provider catalog — serving the Default tier instead.", null);
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

    private static String requestedModel(HttpRequest req, JsonCodec json) {
        if (req.body == null || req.body.isEmpty()) return "";
        try {
            Object el = json.parse(req.body);
            if (el instanceof Map) {
                Object m = ((Map<?, ?>) el).get("model");
                if (m instanceof String) return (String) m;
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
    private static HttpResponse modelsResponse(String path, RouterOptions opts) {
        List<CatalogEntry> raw = ModelMap.catalogEntries(opts.store, opts.json, opts.listProviders.get());
        List<CatalogEntry> entries = new ArrayList<>();
        for (CatalogEntry e : raw) {
            if (!e.model.endsWith("-auto")) entries.add(e);
        }

        // `path` is the raw request-target's path component (still percent-encoded); decode
        // the id remainder EXACTLY ONCE here (JS parity: server.ts decodes the WHATWG URL's
        // raw pathname exactly once) — decoding twice would turn a literal '+' into a space.
        String rawId = path.replaceFirst("^/v1/models/?", "");
        String id = decodeUrlComponentOnce(rawId);
        if (!id.isEmpty()) {
            for (CatalogEntry e : entries) {
                if (e.model.equals(id)) return jsonResponse(200, modelInfo(e, opts), opts.json);
            }
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("type", "not_found_error");
            err.put("message", "model not found: " + id);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("type", "error");
            body.put("error", err);
            return jsonResponse(404, body, opts.json);
        }

        Set<String> seen = new HashSet<>();
        List<Object> data = new ArrayList<>();
        for (CatalogEntry e : entries) {
            if (seen.contains(e.model)) continue; // same id may exist under several providers
            seen.add(e.model);
            data.add(modelInfo(e, opts));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        body.put("first_id", data.isEmpty() ? null : ((Map<?, ?>) data.get(0)).get("id"));
        body.put("last_id", data.isEmpty() ? null : ((Map<?, ?>) data.get(data.size() - 1)).get("id"));
        body.put("has_more", false);
        return jsonResponse(200, body, opts.json);
    }

    private static Map<String, Object> modelInfo(CatalogEntry entry, RouterOptions opts) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "model");
        m.put("id", entry.model);
        m.put("display_name", entry.name != null && !entry.name.isEmpty() ? entry.name : entry.model);
        m.put("created_at", "2025-01-01T00:00:00Z");
        m.put("max_input_tokens", entry.contextLimit != null ? entry.contextLimit : opts.profile.defaultContext);
        m.put("max_tokens", entry.outputLimit != null ? entry.outputLimit : opts.profile.defaultOutput);
        return m;
    }

    // -- shared response builders -----------------------------------------------

    private static HttpResponse errorResponse(int status, String message, JsonCodec json) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("type", "loader_proxy_error");
        err.put("message", message);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "error");
        body.put("error", err);
        return jsonResponse(status, body, json);
    }

    private static HttpResponse jsonResponse(int status, Object body, JsonCodec json) {
        HttpResponse resp = new HttpResponse();
        resp.status = status;
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json");
        resp.headers = headers;
        resp.body = json.stringify(body);
        return resp;
    }

    private static HttpResponse textResponse(int status, String body) {
        HttpResponse resp = new HttpResponse();
        resp.status = status;
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "text/plain");
        resp.headers = headers;
        resp.body = body;
        return resp;
    }

    // -- url helpers (no java.net — hand-rolled for transpilability) ---------------

    private static String pathOf(String url) {
        if (url == null) return "/";
        int q = url.indexOf('?');
        return q >= 0 ? url.substring(0, q) : url;
    }

    // Percent-decodes a URL path component exactly once, without java.net.URLDecoder (not
    // transpilable). ASCII-range and multi-byte UTF-8 percent sequences are both handled.
    private static String decodeUrlComponentOnce(String s) {
        int n = s.length();
        byte[] bytes = new byte[n];
        int len = 0;
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < n) {
                int hi = hexVal(s.charAt(i + 1));
                int lo = hexVal(s.charAt(i + 2));
                if (hi >= 0 && lo >= 0) {
                    bytes[len++] = (byte) ((hi << 4) | lo);
                    i += 3;
                    continue;
                }
            }
            bytes[len++] = (byte) c; // literal ASCII passthrough (model ids are ASCII in practice)
            i++;
        }
        return utf8Decode(bytes, len);
    }

    private static int hexVal(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    private static String utf8Decode(byte[] bytes, int len) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < len) {
            int b0 = bytes[i] & 0xFF;
            if (b0 < 0x80) {
                sb.append((char) b0);
                i++;
            } else if ((b0 & 0xE0) == 0xC0 && i + 1 < len) {
                int b1 = bytes[i + 1] & 0xFF;
                sb.append((char) (((b0 & 0x1F) << 6) | (b1 & 0x3F)));
                i += 2;
            } else if ((b0 & 0xF0) == 0xE0 && i + 2 < len) {
                int b1 = bytes[i + 1] & 0xFF, b2 = bytes[i + 2] & 0xFF;
                sb.append((char) (((b0 & 0x0F) << 12) | ((b1 & 0x3F) << 6) | (b2 & 0x3F)));
                i += 3;
            } else if ((b0 & 0xF8) == 0xF0 && i + 3 < len) {
                int b1 = bytes[i + 1] & 0xFF, b2 = bytes[i + 2] & 0xFF, b3 = bytes[i + 3] & 0xFF;
                int cp = ((b0 & 0x07) << 18) | ((b1 & 0x3F) << 12) | ((b2 & 0x3F) << 6) | (b3 & 0x3F);
                sb.appendCodePoint(cp);
                i += 4;
            } else {
                sb.append((char) b0); // malformed sequence — best-effort passthrough
                i++;
            }
        }
        return sb.toString();
    }

    // -- JSON boundary (Phase 2 TeaVM export surface) --------------------------

    /**
     * String/JSON boundary over {@link #route}: parses {@code {method,url,headers,body}} into
     * an {@link HttpRequest}, routes it, and stringifies the resulting {@link HttpResponse} as
     * {@code {status,headers,body}}. This is the export surface a non-JVM host (e.g. a TeaVM
     * transpile target) calls across the JS/Java boundary with plain strings.
     */
    @SuppressWarnings("unchecked")
    public static String routeJson(String requestJson, RouterOptions opts) {
        Object parsed = opts.json.parse(requestJson);
        HttpRequest req = new HttpRequest();
        req.method = "GET";
        req.url = "/";
        if (parsed instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) parsed;
            Object method = m.get("method");
            Object url = m.get("url");
            Object body = m.get("body");
            Object headers = m.get("headers");
            if (method instanceof String) req.method = (String) method;
            if (url instanceof String) req.url = (String) url;
            if (body instanceof String) req.body = (String) body;
            if (headers instanceof Map) {
                Map<String, String> h = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : ((Map<?, ?>) headers).entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        h.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                    }
                }
                req.headers = h;
            }
        }

        HttpResponse resp = route(req, opts);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", resp.status);
        out.put("headers", resp.headers != null ? resp.headers : new LinkedHashMap<String, String>());
        out.put("body", resp.body != null ? resp.body : "");
        return opts.json.stringify(out);
    }
}
