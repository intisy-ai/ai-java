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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * UI-safe provider-config administration: resolves an installed provider and calls its own
 * {@code GET/PUT /v1/config} branch. Mirrors {@link QuotaAdmin}'s shape (encapsulates the
 * {@link Store}; {@code ManagementApi} never sees it directly). A provider that does not implement
 * the convention answers {@code 404} to {@code GET /v1/config}, which surfaces here as {@code null}
 * so the dashboard can hide the config card instead of showing an error.
 */
public final class ConfigAdmin {
    private final ProviderRegistryHolder holder;
    private final JsonCodec json;
    private final Logger log;
    private final String configDir;
    private final Store store;

    public ConfigAdmin(Store store, JsonCodec json, ProviderRegistryHolder holder, Logger log) {
        this.holder = holder;
        this.json = json;
        this.log = log;
        this.configDir = store instanceof FileStore ? ((FileStore) store).configFolder().toString() : "";
        this.store = store;
    }

    /** {@code {groups,values}}, or {@code null} if the provider answers 404 (no config surface). */
    public Map<String, Object> getConfig(String providerId) {
        HttpResponse response = call(providerId, "GET", "/v1/config", null);
        if (response.status == 404) return null;
        if (response.status / 100 != 2) {
            throw new IllegalArgumentException("provider returned " + response.status + ": " + response.body);
        }
        return asMap(json.parse(response.body));
    }

    /** Persists {@code values} via the provider and returns its re-read {@code {values}}. */
    public Map<String, Object> putConfig(String providerId, Map<String, Object> values) {
        String body = json.stringify(Collections.singletonMap("values", values));
        HttpResponse response = call(providerId, "PUT", "/v1/config", body);
        if (response.status / 100 != 2) {
            throw new IllegalArgumentException("provider returned " + response.status + ": " + response.body);
        }
        return asMap(json.parse(response.body));
    }

    private HttpResponse call(String providerId, String method, String url, String body) {
        ProxyHandler handler = holder.asHandlerResolver().resolve(providerId);
        if (handler == null) {
            throw new IllegalArgumentException("unknown provider: " + providerId);
        }
        HttpRequest request = new HttpRequest();
        request.method = method;
        request.url = url;
        request.headers = new LinkedHashMap<>();
        request.body = body;
        try {
            return handler.handle(request, new HandlerCtx(configDir, store, log, null));
        } catch (Exception e) {
            throw new IllegalArgumentException("config call failed: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }
}
