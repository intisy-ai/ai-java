package io.github.intisy.ai.examples.support;

import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.Store;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the store documents the routing engine reads, using the SAME {@link Store}/{@link JsonCodec}
 * SPIs a real deployment uses — no hand-written JSON strings. Two shapes matter:
 * <ul>
 *   <li>{@code models.json} — the per-provider catalog core-auth would populate on login; the
 *       {@code /v1/models} endpoint and the model-map self-heal both read it.</li>
 *   <li>the profile's own config file ({@code modelMap}) — the tier &rarr; ordered
 *       {provider, model} chains that drive routing and fallback.</li>
 * </ul>
 */
public final class DemoSeeds {

    private DemoSeeds() {
    }

    /**
     * Seeds a catalog + model map for the two jar-loaded example providers ({@code echo} healthy,
     * {@code ratelimited} always-429), arranged so each of the three tiers demonstrates one routing
     * behavior:
     * <ul>
     *   <li>{@code opus} &rarr; [ratelimited, echo]: primary is exhausted, so routing falls back to echo;</li>
     *   <li>{@code haiku} &rarr; [echo]: served straight by the healthy provider (shows model rewrite);</li>
     *   <li>{@code sonnet} &rarr; [ratelimited]: the whole tier is exhausted, so routing synthesizes a native 429.</li>
     * </ul>
     * Every model id referenced by the map also exists in the catalog, so the map resolves without
     * self-healing — the chains route exactly as written.
     */
    public static void seedJarRouting(Store store, JsonCodec json, String configFile) {
        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("echo", providerCatalog(
                model("m-echo-opus", "Echo Opus"),
                model("m-echo-haiku", "Echo Haiku")));
        catalog.put("ratelimited", providerCatalog(
                model("m-busy-opus", "Busy Opus"),
                model("m-busy-sonnet", "Busy Sonnet")));
        store.put("models.json", json.stringify(catalog));

        Map<String, Object> modelMap = new LinkedHashMap<>();
        modelMap.put("opus", Arrays.asList(
                assignment("ratelimited", "m-busy-opus"),
                assignment("echo", "m-echo-opus")));
        modelMap.put("haiku", Arrays.asList(
                assignment("echo", "m-echo-haiku")));
        modelMap.put("sonnet", Arrays.asList(
                assignment("ratelimited", "m-busy-sonnet")));
        store.put(configFile, json.stringify(wrapModelMap(modelMap)));
    }

    /**
     * Seeds a minimal {@code opus} chain of [rl, ok] for the demos that route through an in-process
     * {@code HandlerResolver} (rl always 429, ok always serves) rather than the provider jar — used
     * where the point is storage/SPI swappability, not jar discovery. No catalog is written, so the
     * chain passes through untouched (its providers are unknown to the empty catalog).
     */
    public static void seedInProcessFallback(Store store, JsonCodec json, String configFile) {
        Map<String, Object> modelMap = new LinkedHashMap<>();
        modelMap.put("opus", Arrays.asList(
                assignment("rl", "m-rl"),
                assignment("ok", "m-ok")));
        store.put(configFile, json.stringify(wrapModelMap(modelMap)));
    }

    private static Map<String, Object> wrapModelMap(Map<String, Object> modelMap) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("modelMap", modelMap);
        return doc;
    }

    private static Map<String, Object> assignment(String provider, String model) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("provider", provider);
        entry.put("model", model);
        return entry;
    }

    // A ModelsCache provider entry: {models:{id:{name,limit}}, ranking:[ids...]}.
    @SafeVarargs
    private static Map<String, Object> providerCatalog(Map<String, Object>... models) {
        Map<String, Object> modelsById = new LinkedHashMap<>();
        List<String> ranking = new ArrayList<>();
        for (Map<String, Object> model : models) {
            String id = (String) model.get("__id");
            model.remove("__id");
            modelsById.put(id, model);
            ranking.add(id);
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("models", modelsById);
        entry.put("ranking", ranking);
        return entry;
    }

    private static Map<String, Object> model(String id, String displayName) {
        Map<String, Object> limit = new LinkedHashMap<>();
        limit.put("context", 200000);
        limit.put("output", 64000);
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("__id", id); // consumed by providerCatalog to key the map, then removed
        model.put("name", displayName);
        model.put("limit", limit);
        return model;
    }
}
