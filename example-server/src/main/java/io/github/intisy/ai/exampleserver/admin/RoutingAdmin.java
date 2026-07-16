package io.github.intisy.ai.exampleserver.admin;

import io.github.intisy.ai.exampleserver.discovery.ProviderRegistryHolder;
import io.github.intisy.ai.jvm.backend.store.FileStore;
import io.github.intisy.ai.shared.logic.ModelMap;
import io.github.intisy.ai.shared.routing.HandlerCtx;
import io.github.intisy.ai.shared.routing.ProxyHandler;
import io.github.intisy.ai.shared.routing.RoutingProfile;
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
 * UI-safe routing administration: discovers an installed provider's live model catalog into the
 * shared {@code models.json} cache, and reads/writes the tier -&gt; {provider,model} chain map
 * stored under the {@link RoutingProfile#configFile}. Mirrors {@link AccountAdmin}'s shape
 * (encapsulates the {@link Store}; {@link io.github.intisy.ai.exampleserver.api.ManagementApi}
 * never sees it directly).
 */
public class RoutingAdmin {
    private static final String CATALOG_KEY = "models.json";

    private final Store store;
    private final JsonCodec json;
    private final RoutingProfile profile;
    private final ProviderRegistryHolder holder;
    private final Logger log;
    private final String configDir;

    public RoutingAdmin(Store store, JsonCodec json, RoutingProfile profile,
                         ProviderRegistryHolder holder, Logger log) {
        this.store = store;
        this.json = json;
        this.profile = profile;
        this.holder = holder;
        this.log = log;
        this.configDir = store instanceof FileStore ? ((FileStore) store).configFolder().toString() : "";
    }

    /**
     * Calls the provider's own {@code GET /v1/models} branch and merges the result into
     * {@code models.json} under {@code providerId}, preserving every other provider's entry.
     *
     * @throws IllegalArgumentException if the provider id is unknown, the call throws, or the
     *                                   provider responds with a non-2xx status (its message is
     *                                   carried through so the caller sees why, e.g. no account)
     */
    public Map<String, Object> discover(String providerId) {
        ProxyHandler handler = holder.asHandlerResolver().resolve(providerId);
        if (handler == null) {
            throw new IllegalArgumentException("unknown provider: " + providerId);
        }

        HttpRequest request = new HttpRequest();
        request.method = "GET";
        request.url = "/v1/models";
        request.headers = new LinkedHashMap<>();
        HandlerCtx ctx = new HandlerCtx(configDir, log, null);

        HttpResponse response;
        try {
            response = handler.handle(request, ctx);
        } catch (Exception e) {
            throw new IllegalArgumentException("discovery failed: " + e.getMessage());
        }
        if (response.status / 100 != 2) {
            throw new IllegalArgumentException("provider returned " + response.status + ": " + response.body);
        }

        Map<String, Object> parsed = asMap(json.parse(response.body));
        Map<String, Object> models = parsed != null ? asMap(parsed.get("models")) : null;
        if (models == null) models = new LinkedHashMap<>();
        List<String> ranking = parsed != null ? asStringList(parsed.get("ranking")) : null;
        if (ranking == null) ranking = new ArrayList<>(models.keySet());

        Map<String, Object> catalog = readCatalog();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("models", models);
        entry.put("ranking", ranking);
        catalog.put(providerId, entry);
        store.put(CATALOG_KEY, json.stringify(catalog));

        return discoverResult(providerId, models, ranking);
    }

    private static Map<String, Object> discoverResult(String providerId, Map<String, Object> models, List<String> ranking) {
        List<Map<String, Object>> modelList = new ArrayList<>();
        for (String id : ranking) {
            Map<String, Object> modelObj = asMap(models.get(id));
            Object name = modelObj != null ? modelObj.get("name") : null;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            item.put("name", name instanceof String && !((String) name).isEmpty() ? name : id);
            modelList.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", providerId);
        result.put("models", modelList);
        return result;
    }

    /** Raw {@code models.json}, or {@code "{}"} if never populated. */
    public String catalogJson() {
        String raw = store.get(CATALOG_KEY);
        return raw != null ? raw : "{}";
    }

    /** {@code {tiers: <detected tier names>, map: <raw stored tier map>}}. */
    public Map<String, Object> modelMapView() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("tiers", ModelMap.resolveTiers(store, json, profile));
        view.put("map", ModelMap.readModelMap(store, json, profile));
        return view;
    }

    /**
     * Validates and persists a full tier -&gt; chain map. Every {@code provider} must be one of
     * {@link ProviderRegistryHolder#listProviderIds()} (unknown -&gt; throws); an unknown
     * {@code model} for an otherwise-known provider is not fatal (the router self-heals it) but
     * is surfaced back as a warning.
     */
    public Map<String, Object> putModelMap(Map<String, Object> map) {
        List<String> providerIds = holder.listProviderIds();
        Map<String, Object> catalog = readCatalog();
        List<String> warnings = new ArrayList<>();

        for (Object rawChain : map.values()) {
            for (Object rawEntry : asChain(rawChain)) {
                Map<?, ?> entry = rawEntry instanceof Map ? (Map<?, ?>) rawEntry : null;
                if (entry == null) continue;
                String provider = stringOf(entry.get("provider"));
                String model = stringOf(entry.get("model"));
                if (provider == null || !providerIds.contains(provider)) {
                    throw new IllegalArgumentException("unknown provider in map: " + provider);
                }
                if (model != null && !catalogHasModel(catalog, provider, model)) {
                    warnings.add(provider + "/" + model);
                }
            }
        }

        store.put(profile.configFile, json.stringify(Collections.singletonMap("modelMap", map)));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("warnings", warnings);
        return result;
    }

    // A stored slot is either a single {provider,model} object (legacy) or an ordered chain
    // (list of them); normalize both shapes to a list so validation is one loop.
    private static List<?> asChain(Object raw) {
        if (raw instanceof List) return (List<?>) raw;
        return raw != null ? Collections.singletonList(raw) : Collections.emptyList();
    }

    private static boolean catalogHasModel(Map<String, Object> catalog, String provider, String model) {
        Map<String, Object> providerEntry = asMap(catalog.get(provider));
        Map<String, Object> models = providerEntry != null ? asMap(providerEntry.get("models")) : null;
        return models != null && models.containsKey(model);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readCatalog() {
        String raw = store.get(CATALOG_KEY);
        if (raw == null) return new LinkedHashMap<>();
        Object parsed = json.parse(raw);
        return parsed instanceof Map ? new LinkedHashMap<>((Map<String, Object>) parsed) : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    private static List<String> asStringList(Object o) {
        if (!(o instanceof List)) return null;
        List<String> out = new ArrayList<>();
        for (Object item : (List<?>) o) {
            if (item instanceof String) out.add((String) item);
        }
        return out;
    }

    private static String stringOf(Object o) {
        return o instanceof String ? (String) o : null;
    }
}
