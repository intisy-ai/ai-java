package io.github.intisy.ai.exampleserver.admin;

import io.github.intisy.ai.exampleserver.discovery.ProviderRegistryHolder;
import io.github.intisy.ai.jvm.backend.store.FileStore;
import io.github.intisy.ai.shared.routing.HandlerCtx;
import io.github.intisy.ai.shared.routing.ProxyHandler;
import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.Logger;
import io.github.intisy.ai.shared.spi.Store;
import io.github.intisy.ai.shared.spi.http.HttpRequest;
import io.github.intisy.ai.shared.spi.http.HttpResponse;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * UI-safe quota administration: resolves an installed provider and calls its own
 * {@code GET /v1/quota} branch, returning the parsed {@code {accounts:[...]}} map. Mirrors
 * {@link RoutingAdmin}'s shape (encapsulates the {@link Store}; {@code ManagementApi} never sees
 * it directly).
 */
public final class QuotaAdmin {
    private final ProviderRegistryHolder holder;
    private final JsonCodec json;
    private final Logger log;
    private final String configDir;

    public QuotaAdmin(Store store, JsonCodec json, ProviderRegistryHolder holder, Logger log) {
        this.holder = holder;
        this.json = json;
        this.log = log;
        this.configDir = store instanceof FileStore ? ((FileStore) store).configFolder().toString() : "";
    }

    /**
     * Resolve the provider + call its {@code GET /v1/quota}; returns the parsed
     * {@code {accounts:[...]}} map.
     *
     * @throws IllegalArgumentException if the provider id is unknown, the call throws, or the
     *                                   provider responds with a non-2xx status (its message is
     *                                   carried through so the caller sees why, e.g. no account)
     */
    public Map<String, Object> refresh(String providerId) {
        ProxyHandler handler = holder.asHandlerResolver().resolve(providerId);
        if (handler == null) {
            throw new IllegalArgumentException("unknown provider: " + providerId);
        }

        HttpRequest request = new HttpRequest();
        request.method = "GET";
        request.url = "/v1/quota";
        request.headers = new LinkedHashMap<>();

        HttpResponse response;
        try {
            response = handler.handle(request, new HandlerCtx(configDir, log, null));
        } catch (Exception e) {
            throw new IllegalArgumentException("quota fetch failed: " + e.getMessage());
        }
        if (response.status / 100 != 2) {
            throw new IllegalArgumentException("provider returned " + response.status + ": " + response.body);
        }

        Object parsed = json.parse(response.body);
        return parsed instanceof Map ? castMap(parsed) : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }
}
