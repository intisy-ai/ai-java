package io.github.intisy.ai.exampleserver.admin;

import io.github.intisy.ai.exampleserver.discovery.ProviderRegistryHolder;
import io.github.intisy.ai.jvm.backend.store.FileStore;
import io.github.intisy.ai.shared.logic.ModelMap;
import io.github.intisy.ai.shared.routing.HandlerCtx;
import io.github.intisy.ai.shared.routing.ModelCatalogProvider;
import io.github.intisy.ai.shared.routing.ModelInfo;
import io.github.intisy.ai.shared.routing.Provider;
import io.github.intisy.ai.shared.routing.RoutingProfile;
import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.Logger;
import io.github.intisy.ai.shared.spi.Store;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * UI-safe routing administration: discovers an installed provider's live model catalog into the
 * shared {@code models.json} cache, and reads/writes the tier -&gt; {provider,model} chain map
 * stored under a caller-supplied {@link RoutingProfile#configFile}. Routing is per-INSTALLED-PROXY
 * only (there is no built-in default profile -- the {@code ExampleServer} console reaches providers
 * directly, never through a router; see the per-proxy {@code ProxyServer}), so every model-map
 * method here takes its {@link RoutingProfile} as an explicit argument rather than a ctor default.
 * Mirrors {@link AccountAdmin}'s shape (encapsulates the {@link Store}; {@link
 * io.github.intisy.ai.exampleserver.api.ManagementApi} never sees it directly).
 */
public class RoutingAdmin {
    private static final String CATALOG_KEY = "models.json";

    private final Store store;
    private final JsonCodec json;
    private final ProviderRegistryHolder holder;
    private final Logger log;
    private final String configDir;

    public RoutingAdmin(Store store, JsonCodec json, ProviderRegistryHolder holder, Logger log) {
        this.store = store;
        this.json = json;
        this.holder = holder;
        this.log = log;
        this.configDir = store instanceof FileStore ? ((FileStore) store).configFolder().toString() : "";
    }

    /**
     * Calls the provider's typed {@link ModelCatalogProvider#models} and merges the result into
     * {@code models.json} under {@code providerId}, preserving every other provider's entry.
     *
     * @throws IllegalArgumentException if the provider id is unknown, or the provider does not
     *                                   implement {@link ModelCatalogProvider} (discover is an
     *                                   explicit user action; erroring is fine)
     */
    public Map<String, Object> discover(String providerId) {
        Provider p = holder.get(providerId);
        if (p == null) {
            throw new IllegalArgumentException("unknown provider: " + providerId);
        }
        if (!(p instanceof ModelCatalogProvider)) {
            throw new IllegalArgumentException("provider has no model catalog: " + providerId);
        }

        HandlerCtx ctx = new HandlerCtx(configDir, store, log, null);
        List<ModelInfo> modelInfos = ((ModelCatalogProvider) p).models(ctx);
        if (modelInfos == null) modelInfos = new ArrayList<>();

        Map<String, Object> models = new LinkedHashMap<>();
        List<String> ranking = new ArrayList<>();
        for (ModelInfo m : modelInfos) {
            Map<String, Object> modelEntry = new LinkedHashMap<>();
            modelEntry.put("name", m.name);
            Map<String, Object> limit = new LinkedHashMap<>();
            limit.put("context", m.context);
            limit.put("output", m.output);
            modelEntry.put("limit", limit);
            models.put(m.id, modelEntry);
            ranking.add(m.id);
        }

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

    /**
     * Removes {@code providerId}'s entry from the stored catalog ({@code models.json}), no-op if
     * absent. Called after a successful uninstall so a later reinstall discovers a fresh catalog
     * instead of reading back whatever the old install last cached.
     */
    public void removeFromCatalog(String providerId) {
        Map<String, Object> catalog = readCatalog();
        if (catalog.remove(providerId) != null) {
            store.put(CATALOG_KEY, json.stringify(catalog));
        }
    }

    /**
     * {@code {tiers: <declared union detected tier names>, map: <raw stored tier map>}} for the
     * given profile's config file.
     */
    public Map<String, Object> modelMapView(RoutingProfile profile) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("tiers", unionTiers(profile));
        view.put("map", ModelMap.readModelMap(store, json, profile));
        return view;
    }

    /**
     * Union of the tiers actually DETECTED in the discovered catalog ({@link ModelMap#resolveTiers},
     * unchanged/still detection-based for the live router) with every slot the profile DECLARES
     * via {@link RoutingProfile#tierOrder} -- so an operator can pre-configure a not-yet-discovered
     * slot (e.g. {@code fable}) before its first model ever shows up in the catalog. Declared order
     * first, any detected-but-undeclared tier appended after (in detected order); de-duped.
     */
    private List<String> unionTiers(RoutingProfile profile) {
        List<String> detected = ModelMap.resolveTiers(store, json, profile);
        List<String> union = new ArrayList<>();
        for (String tier : profile.tierOrder) {
            if (!union.contains(tier)) union.add(tier);
        }
        for (String tier : detected) {
            if (!union.contains(tier)) union.add(tier);
        }
        return union;
    }

    /**
     * Validates and persists a full tier -&gt; chain map into the given profile's config file.
     * Every {@code provider} must be one of {@link ProviderRegistryHolder#listProviderIds()}
     * (unknown -&gt; throws); an unknown {@code model} for an otherwise-known provider is not
     * fatal (the router self-heals it) but is surfaced back as a warning.
     */
    public Map<String, Object> putModelMap(RoutingProfile profile, Map<String, Object> map) {
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

    private static String stringOf(Object o) {
        return o instanceof String ? (String) o : null;
    }
}
