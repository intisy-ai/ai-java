package io.github.intisy.ai.exampleserver.admin;

import io.github.intisy.ai.exampleserver.discovery.ProviderRegistryHolder;
import io.github.intisy.ai.ir.IrRequest;
import io.github.intisy.ai.ir.IrResponse;
import io.github.intisy.ai.ir.spi.Translator;
import io.github.intisy.ai.seam.jvm.FileStore;
import io.github.intisy.ai.jvm.translator.TranslatorRegistry;
import io.github.intisy.ai.ir.spi.HandleIrException;
import io.github.intisy.ai.ir.spi.HandlerCtx;
import io.github.intisy.ai.auth.contracts.Provider;
import io.github.intisy.ai.api.seam.JsonCodec;
import io.github.intisy.ai.api.seam.Logger;
import io.github.intisy.ai.api.seam.Store;
import io.github.intisy.ai.api.seam.HttpRequest;
import io.github.intisy.ai.api.seam.HttpResponse;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Console chat = DIRECT provider access. The dashboard runs in the SAME JVM as the providers, so a
 * chat request never needs the routing engine (that's the per-proxy {@code ProxyServer}'s job, for
 * out-of-process apps that connect over HTTP and need their request interpreted) -- it resolves the
 * provider straight out of the {@link ProviderRegistryHolder} by the id in the URL. Mirrors {@link
 * ConfigAdmin}/{@link QuotaAdmin}'s shape (encapsulates the {@link Store}; {@code ManagementApi}
 * never sees it directly).
 *
 * <p>This is the console's own IR front-door. {@code send} decodes the inbound {@code body} into
 * IR via this admin's own {@link #translator} (console chat has no {@code RoutingProfile} -- its
 * wire format is whichever {@link Translator} the staged translators directory provides, not a
 * per-app concern), calls the resolved provider's {@link Provider#handleIr}, and encodes the
 * result back to wire JSON -- mirroring core-proxy's Router#route one level up, without a router
 * in between. A provider with no IR path (the {@link Provider#handleIr} default) throws {@link
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
    private final String translatorError; // set only when MORE THAN ONE translator was found

    public MessagesAdmin(Store store, JsonCodec json, ProviderRegistryHolder holder, Logger log) {
        this.holder = holder;
        this.json = json;
        this.log = log;
        this.configDir = store instanceof FileStore ? ((FileStore) store).configFolder().toString() : "";
        this.store = store;
        TranslatorResolution resolution = resolveTranslator();
        this.translator = resolution.translator;
        this.translatorError = resolution.error;
    }

    /**
     * @implNote Zero found (no translator jar staged) is NOT treated as fatal here, unlike {@code
     * ServerProfile}: this admin also serves provider-agnostic dashboard actions that don't need a
     * translator at all, so {@link #send} degrades with an explicit error at request time instead
     * of failing every console feature at construction. MORE THAN ONE found is also non-fatal here
     * for the same reason, but is distinguished from "none" so {@link #send} can report the real
     * cause (an ambiguous directory) instead of the misleading "no translator" message. Resolved
     * fresh per instance (no caching): {@code MessagesAdmin} is constructed once per server
     * process, so re-scanning costs nothing, and staying uncached keeps it honest about whatever
     * directory {@code exampleserver.translatorsDir} currently points at.
     */
    private static TranslatorResolution resolveTranslator() {
        File dir = new File(System.getProperty("exampleserver.translatorsDir", "translators"));
        List<Translator> found = TranslatorRegistry.fromDirectory(dir.toPath()).translators();
        if (found.size() > 1) {
            // File.listFiles order is unspecified -- picking one arbitrarily here would be a
            // silent, nondeterministic misconfiguration instead of a loud one.
            return new TranslatorResolution(null, "found " + found.size()
                    + " Translator implementations in " + dir.getAbsolutePath()
                    + "; stage exactly one translator jar");
        }
        return new TranslatorResolution(found.isEmpty() ? null : found.get(0), null);
    }

    private static final class TranslatorResolution {
        final Translator translator;
        final String error;

        TranslatorResolution(Translator translator, String error) {
            this.translator = translator;
            this.error = error;
        }
    }

    /**
     * Resolves {@code providerId} and serves {@code body} (a request shaped for whichever {@link
     * Translator} is staged) -- NO router, NO model-&gt;provider resolution, NO fallback chain. The
     * provider id comes from the URL; the concrete model the caller wants is read out of {@code
     * body.model} and threaded through {@link HandlerCtx#model}, exactly like the router does for
     * an already-resolved assignment.
     */
    public HttpResponse send(String providerId, String body) {
        // No (or ambiguous) translator staged: fail visibly here rather than silently falling
        // through to decodeIr() returning null, which would look identical to an ordinary
        // malformed body.
        if (translator == null) {
            return translatorError != null
                    ? errorResponse(503, "ambiguous_translator", translatorError)
                    : errorResponse(503, "no_translator",
                            "no Translator implementation is staged; console chat needs a translator jar (see :examples-translator)");
        }

        Provider p = holder.get(providerId);
        if (p == null) {
            return errorResponse(404, "not_found", "unknown provider: " + providerId);
        }

        IrRequest irRequest = decodeIr(body);
        if (irRequest == null) {
            return errorResponse(400, "invalid_request_error",
                    "body did not decode as the staged translator's wire format");
        }

        HandlerCtx ctx = new HandlerCtx(configDir, store, log, modelOf(body));
        try {
            return wireResponse(translator.encodeResponse(p.handleIr(irRequest, ctx)));
        } catch (HandleIrException hie) {
            return responseFromHandleIrException(hie);
        } catch (Throwable e) {
            // Throwable, not Exception: a provider on the real upstream path can throw a
            // LinkageError/NoClassDefFoundError (e.g. a classloader mismatch), and letting that
            // escape drops the HTTP connection, which the browser shows as a bare "NetworkError"
            // with no clue what actually failed.
            return errorResponse(502, "api_error", "chat failed: " + e);
        }
    }

    // Decodes body through this admin's own translator; null (never throws) on any decode failure,
    // whether the body is malformed, shaped for a different translator, or absent.
    private IrRequest decodeIr(String body) {
        if (body == null || body.isEmpty()) return null;
        try {
            return translator.decodeRequest(body);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * @implNote Mirrors core-proxy's {@code Router#route} {@code HandleIrException} handling:
     * reconstructs the provider's real upstream status/headers/body instead of collapsing to a
     * flat 502, and surfaces {@link HandleIrException#retryAfterMs} as {@code
     * x-hub-retry-after-ms} (only when the provider didn't already set it) so rate-limit
     * information isn't silently dropped.
     */
    private HttpResponse responseFromHandleIrException(HandleIrException hie) {
        HttpResponse resp = new HttpResponse();
        resp.status = hie.status;
        Map<String, String> headers = hie.headers != null ? new LinkedHashMap<>(hie.headers) : new LinkedHashMap<>();
        if (hie.retryAfterMs != null && !hasHeaderIgnoreCase(headers, "x-hub-retry-after-ms")) {
            headers.put("x-hub-retry-after-ms", String.valueOf(hie.retryAfterMs));
        }
        resp.headers = headers;
        resp.body = hie.body;
        return resp;
    }

    private static boolean hasHeaderIgnoreCase(Map<String, String> headers, String name) {
        for (String key : headers.keySet()) {
            if (key != null && key.equalsIgnoreCase(name)) return true;
        }
        return false;
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
