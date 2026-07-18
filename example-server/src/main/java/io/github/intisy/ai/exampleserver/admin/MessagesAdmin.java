package io.github.intisy.ai.exampleserver.admin;

import io.github.intisy.ai.exampleserver.discovery.ProviderRegistryHolder;
import io.github.intisy.ai.jvm.backend.store.FileStore;
import io.github.intisy.ai.shared.routing.HandlerCtx;
import io.github.intisy.ai.shared.routing.Provider;
import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.Logger;
import io.github.intisy.ai.shared.spi.Store;
import io.github.intisy.ai.shared.spi.http.HttpRequest;
import io.github.intisy.ai.shared.spi.http.HttpResponse;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Console chat = DIRECT provider access. The dashboard runs in the SAME JVM as the providers, so a
 * chat request never needs the routing engine (that's the per-proxy {@code ProxyServer}'s job, for
 * out-of-process apps that connect over HTTP and need their request interpreted) -- it resolves the
 * provider straight out of the {@link ProviderRegistryHolder} by the id in the URL and calls its
 * typed {@link Provider#handle} directly. Mirrors {@link ConfigAdmin}/{@link QuotaAdmin}'s shape
 * (encapsulates the {@link Store}; {@code ManagementApi} never sees it directly), except the result
 * here is the provider's raw {@link HttpResponse} written back VERBATIM -- an Anthropic-messages
 * response (or the provider's own error shape/headers) already IS the wire body, so there is
 * nothing to re-wrap into a {@code {...}} admin result the way {@link ConfigAdmin}/{@link
 * QuotaAdmin} do.
 */
public final class MessagesAdmin {
    private final ProviderRegistryHolder holder;
    private final JsonCodec json;
    private final Logger log;
    private final String configDir;
    private final Store store;

    public MessagesAdmin(Store store, JsonCodec json, ProviderRegistryHolder holder, Logger log) {
        this.holder = holder;
        this.json = json;
        this.log = log;
        this.configDir = store instanceof FileStore ? ((FileStore) store).configFolder().toString() : "";
        this.store = store;
    }

    /**
     * Calls {@code providerId}'s {@link Provider#handle} directly with {@code body} passed through
     * verbatim as the request -- NO router, NO model-&gt;provider resolution, NO fallback chain.
     * The provider id comes from the URL; the concrete model the caller wants is read out of
     * {@code body.model} and threaded through {@link HandlerCtx#model}, exactly like the router
     * does for an already-resolved assignment.
     */
    public HttpResponse send(String providerId, String body) {
        Provider p = holder.get(providerId);
        if (p == null) {
            return errorResponse(404, "not_found", "unknown provider: " + providerId);
        }

        String model = modelOf(body);
        HttpRequest req = new HttpRequest();
        req.method = "POST";
        req.url = "/v1/messages";
        req.headers = new LinkedHashMap<>();
        req.body = body;
        HandlerCtx ctx = new HandlerCtx(configDir, store, log, model);
        try {
            return p.handle(req, ctx);
        } catch (Exception e) {
            return errorResponse(502, "api_error", "chat failed: " + e.getMessage());
        }
    }

    // Best-effort read of the top-level "model" string field; malformed/absent -> null, leaving it
    // to the provider itself to reject a request it can't make sense of.
    private String modelOf(String body) {
        Object parsed;
        try {
            parsed = json.parse(body);
        } catch (RuntimeException e) {
            return null;
        }
        if (!(parsed instanceof Map)) return null;
        Object model = ((Map<?, ?>) parsed).get("model");
        return model instanceof String ? (String) model : null;
    }

    // Anthropic-shaped {"type":"error","error":{"type":..,"message":..}} -- mirrors
    // core-proxy's Router#errorResponse so a caller sees the same error envelope regardless of
    // whether a request went through the router (ProxyServer) or straight to a provider (here).
    private HttpResponse errorResponse(int status, String type, String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("type", type);
        err.put("message", message);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "error");
        body.put("error", err);

        HttpResponse resp = new HttpResponse();
        resp.status = status;
        resp.headers = new LinkedHashMap<>();
        resp.headers.put("content-type", "application/json");
        resp.body = json.stringify(body);
        return resp;
    }
}
