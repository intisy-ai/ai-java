package io.github.intisy.ai.exampleserver.admin;

import io.github.intisy.ai.exampleserver.discovery.ProviderRegistryHolder;
import io.github.intisy.ai.exampleserver.ir.RoutingJsonCodecAdapter;
import io.github.intisy.ai.ir.IrRequest;
import io.github.intisy.ai.ir.IrResponse;
import io.github.intisy.ai.ir.spi.Translator;
import io.github.intisy.ai.ir.translators.anthropic.AnthropicTranslator;
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
 * provider straight out of the {@link ProviderRegistryHolder} by the id in the URL. Mirrors {@link
 * ConfigAdmin}/{@link QuotaAdmin}'s shape (encapsulates the {@link Store}; {@code ManagementApi}
 * never sees it directly).
 *
 * <p>This is the console's own IR front-door. {@code send} decodes the inbound Anthropic-shaped
 * {@code body} into IR via this admin's own {@link #translator} (console chat has no {@code
 * RoutingProfile} -- it is a fixed, always-Anthropic wire format, not a per-app concern), calls the
 * resolved provider's {@link Provider#handleIr}, and encodes the result back to wire JSON --
 * mirroring core-proxy's Router#route one level up, without a router in between. A
 * provider with no IR path (the {@link Provider#handleIr} default) throws {@link
 * UnsupportedOperationException}; that specific exception falls back to the legacy {@link
 * Provider#handle} call unchanged, exactly like Router's own fallback -- so a provider (or
 * in-tree fixture) without an IR implementation keeps working with zero changes here.
 */
public final class MessagesAdmin {
    private final ProviderRegistryHolder holder;
    private final JsonCodec json;
    private final Logger log;
    private final String configDir;
    private final Store store;
    private final Translator translator;

    public MessagesAdmin(Store store, JsonCodec json, ProviderRegistryHolder holder, Logger log) {
        this.holder = holder;
        this.json = json;
        this.log = log;
        this.configDir = store instanceof FileStore ? ((FileStore) store).configFolder().toString() : "";
        this.store = store;
        this.translator = new AnthropicTranslator(new RoutingJsonCodecAdapter(json));
    }

    /**
     * Resolves {@code providerId} and serves {@code body} (an Anthropic {@code /v1/messages}-shaped
     * request) -- NO router, NO model-&gt;provider resolution, NO fallback chain. The provider id
     * comes from the URL; the concrete model the caller wants is read out of {@code body.model} and
     * threaded through {@link HandlerCtx#model}, exactly like the router does for an
     * already-resolved assignment.
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

        IrRequest irRequest = decodeIr(body);
        if (irRequest != null) {
            try {
                IrResponse irResponse = p.handleIr(irRequest, ctx);
                return wireResponse(translator.encodeResponse(irResponse));
            } catch (UnsupportedOperationException notIrCapable) {
                // This provider has no IR path (Provider#handleIr's own default) -- fall through
                // to the legacy handle() call below, unchanged.
            } catch (Throwable e) {
                // A provider's handleIr THROWS instead of returning an error
                // IrResponse for any outcome that can't be expressed as a served IR message (a
                // non-2xx upstream response, a synthesized error, ...) -- see Provider#handleIr's
                // own contract. No structured status/body travels with that exception (only its
                // message text does), so surface the exception itself verbatim rather than
                // collapsing it to an opaque wrapper -- exactly mirroring core-proxy's own
                // Router#route, which treats ANY handleIr exception identically: a flat
                // 502 whose message is the thrown exception's own text.
                return errorResponse(502, "api_error", "chat failed: " + e);
            }
        }

        try {
            return p.handle(req, ctx);
        } catch (Throwable e) {
            // Throwable, not Exception: a provider on the real upstream path can throw a
            // LinkageError/NoClassDefFoundError (e.g. a classloader mismatch), and letting that
            // escape drops the HTTP connection -- the browser then shows a bare "NetworkError"
            // with no clue what actually failed. Catching it here turns it into a readable
            // Anthropic-shaped 502 instead, same as an ordinary provider Exception.
            return errorResponse(502, "api_error", "chat failed: " + e);
        }
    }

    // Decodes body through this admin's own translator; null (never throws) on any decode failure
    // (malformed/non-Anthropic-shaped JSON, or no body at all) so the caller falls back to the
    // legacy handle() path, the same "decode failure -> legacy path" convention core-proxy's
    // Router#decodeIr uses.
    private IrRequest decodeIr(String body) {
        if (body == null || body.isEmpty()) return null;
        try {
            return translator.decodeRequest(body);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private HttpResponse wireResponse(String wireJson) {
        HttpResponse resp = new HttpResponse();
        resp.status = 200;
        resp.headers = new LinkedHashMap<>();
        resp.headers.put("content-type", "application/json");
        resp.body = wireJson;
        return resp;
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
