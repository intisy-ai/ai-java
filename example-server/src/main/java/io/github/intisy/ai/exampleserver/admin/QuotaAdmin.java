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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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
    private final Store store;

    public QuotaAdmin(Store store, JsonCodec json, ProviderRegistryHolder holder, Logger log) {
        this.holder = holder;
        this.json = json;
        this.log = log;
        this.configDir = store instanceof FileStore ? ((FileStore) store).configFolder().toString() : "";
        this.store = store;
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
            response = handler.handle(request, new HandlerCtx(configDir, store, log, null));
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

    /**
     * {@link #refresh} plus a per-label aggregate across the provider's accounts: for each pool
     * {@code label}, {@code remainingFraction} is the MEAN across accounts that report it
     * (error/no-quota accounts are excluded from the mean but still counted in
     * {@code accountCount}). Backward-compatible superset -- the raw {@code accounts} array is
     * untouched.
     */
    public Map<String, Object> combined(String providerId) {
        Map<String, Object> raw = refresh(providerId);
        Object accountsObj = raw.get("accounts");
        List<?> accounts = accountsObj instanceof List ? (List<?>) accountsObj : Collections.emptyList();

        Map<String, double[]> byLabel = new LinkedHashMap<>(); // label -> {fractionSum, accountCount}
        for (Object a : accounts) {
            if (!(a instanceof Map)) continue;
            Object q = ((Map<?, ?>) a).get("quota");
            if (!(q instanceof List)) continue;
            for (Object pool : (List<?>) q) {
                if (!(pool instanceof Map)) continue;
                Map<?, ?> p = (Map<?, ?>) pool;
                Object label = p.get("label");
                Object frac = p.get("remainingFraction");
                if (!(label instanceof String) || !(frac instanceof Number)) continue;
                double[] acc = byLabel.computeIfAbsent((String) label, k -> new double[2]);
                acc[0] += ((Number) frac).doubleValue();
                acc[1] += 1;
            }
        }

        List<Map<String, Object>> pools = new ArrayList<>();
        for (Map.Entry<String, double[]> e : byLabel.entrySet()) {
            Map<String, Object> pool = new LinkedHashMap<>();
            pool.put("label", e.getKey());
            pool.put("remainingFraction", e.getValue()[1] > 0 ? e.getValue()[0] / e.getValue()[1] : 0.0);
            pool.put("accounts", (int) e.getValue()[1]);
            pools.add(pool);
        }

        Map<String, Object> combined = new LinkedHashMap<>();
        combined.put("pools", pools);
        combined.put("accountCount", accounts.size());

        Map<String, Object> result = new LinkedHashMap<>(raw);
        result.put("combined", combined);
        return result;
    }
}
