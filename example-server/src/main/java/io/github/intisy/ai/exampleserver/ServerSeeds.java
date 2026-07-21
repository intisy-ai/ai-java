package io.github.intisy.ai.exampleserver;

import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.Store;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Seeds the store a real deployment's core-auth would otherwise populate on login: a per-provider
 * catalog ({@code models.json}) and the tier &rarr; ordered {provider, model} chains. Arranged so
 * {@code haiku=[echo]} serves straight, {@code opus=[ratelimited,echo]} falls back to echo, and
 * {@code sonnet=[ratelimited]} exhausts to a synthesized 429, the same fixture the demos use.
 */
public final class ServerSeeds {

    private ServerSeeds() {
    }

    public static void seedEcho(Store store, JsonCodec json, String configFile) {
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
        modelMap.put("haiku", Arrays.asList(assignment("echo", "m-echo-haiku")));
        modelMap.put("sonnet", Arrays.asList(assignment("ratelimited", "m-busy-sonnet")));
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("modelMap", modelMap);
        store.put(configFile, json.stringify(doc));
    }

    private static Map<String, Object> assignment(String provider, String model) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("provider", provider);
        entry.put("model", model);
        return entry;
    }

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
        model.put("__id", id);
        model.put("name", displayName);
        model.put("limit", limit);
        return model;
    }
}
