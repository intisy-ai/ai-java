package io.github.intisy.ai.shared.logic;

import io.github.intisy.ai.shared.routing.Assignment;
import io.github.intisy.ai.shared.routing.CatalogEntry;
import io.github.intisy.ai.shared.routing.RoutingProfile;
import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.Store;
import io.github.intisy.ai.shared.store.ModelsCache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * Shared tier -&gt; provider-model resolution engine, parameterized entirely by a
 * {@link RoutingProfile} (no hardcoded tier/config/env literals). Java port of
 * {@code libs/core-proxy/src/model-map.ts}, used by the proxy (routing), a Providers-style
 * display, and the wrapper (model env injection).
 *
 * <p>Rewired onto the {@link Store} + {@link JsonCodec} SPIs (no gson/nio): {@link #readModelMap}
 * reads the store key {@code profile.configFile} directly, and {@link #catalogEntries} reads
 * the shared, Store-backed {@link ModelsCache} instead of the old nio-based one.
 *
 * <p>Self-heals: a stored mapping whose model no longer exists in the live catalog (e.g.
 * after a model refresh) is auto-re-derived to the current best model for that tier
 * WITHIN the provider the user chose, so the mapping tracks the app's models without the
 * user re-assigning, and never silently crosses to a different provider. Only a tier with
 * NO stored choice at all derives from the whole catalog.
 */
public final class ModelMap {

    // core-auth writes the live per-provider catalog here on login / "Refresh models";
    // the legacy name is read as a fallback only (pre-rename).
    private static final String[] MODEL_CACHE_KEYS = {"models.json", "core-auth-models.json"};

    private ModelMap() {
    }

    /** A {key,value} env pair. Kept as two plain fields, not a pre-joined line, since
     *  values (display names) can contain spaces/parens that the caller must quote per shell. */
    public static class KV {
        public final String key;
        public final String value;

        public KV(String key, String value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public String toString() {
            return key + "=" + value;
        }
    }

    // -- tiers --------------------------------------------------------------------

    /**
     * Tiers are DETECTED from the tier-source provider's catalog (family token of each
     * model id, via {@code profile.tierRegex}, e.g. {@code claude-fable-5 -> "fable"}), so
     * new families appear as mapping slots automatically. {@code profile.tierOrder} keeps
     * known families in a familiar order; {@code profile.tierFallback} covers pre-login (no
     * catalog yet). Java port of the JS {@code claudeTiers}, renamed generically.
     */
    public static List<String> resolveTiers(Store store, JsonCodec json, RoutingProfile p) {
        ModelsCache cache = new ModelsCache(store, json);
        ModelsCache.Entry cc = cache.read(p.tierSourceProvider);
        List<String> ids;
        if (cc != null && cc.ranking != null && !cc.ranking.isEmpty()) {
            ids = cc.ranking;
        } else if (cc != null && cc.models != null) {
            ids = new ArrayList<>(cc.models.keySet());
        } else {
            ids = Collections.emptyList();
        }

        List<String> tiers = new ArrayList<>();
        for (String id : ids) {
            Matcher m = p.tierRegex.matcher(String.valueOf(id));
            if (m.find() && !tiers.contains(m.group(1))) tiers.add(m.group(1));
        }
        if (tiers.isEmpty()) return new ArrayList<>(p.tierFallback);

        tiers.sort((a, b) -> {
            int ia = p.tierOrder.indexOf(a);
            int ib = p.tierOrder.indexOf(b);
            int cmp = Integer.compare(ia < 0 ? 99 : ia, ib < 0 ? 99 : ib);
            return cmp != 0 ? cmp : a.compareTo(b);
        });
        return tiers;
    }

    // -- stored map --------------------------------------------------------------

    /** Reads the store key {@code p.configFile}'s {@code modelMap} object, or {} on any
     *  absence/parse failure. Java port of the JS {@code readModelMap}. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> readModelMap(Store store, JsonCodec json, RoutingProfile p) {
        try {
            String raw = store.get(p.configFile);
            if (raw != null) {
                Object parsed = json.parse(raw);
                if (parsed instanceof Map) {
                    Object mm = ((Map<?, ?>) parsed).get("modelMap");
                    if (mm instanceof Map) return (Map<String, Object>) mm;
                }
            }
        } catch (Exception ignored) {
            // swallow-all, mirrors the JS readModelMap's try/catch degrading to {}
        }
        return new LinkedHashMap<>();
    }

    // -- catalog --------------------------------------------------------------

    /**
     * Live catalog for the given provider ids, read from the shared {@link ModelsCache}
     * (store key {@code models.json}), preferring each provider's ranking (best first) when
     * core-auth computed one, else catalog order.
     *
     * <p>Seam note: the JS original ({@code catalogEntries(configDir)}, no provider-id
     * argument) additionally scanned {@code repos/*}/package.json for each locally deployed
     * plugin's declared {@code authProviders}, and fell back to a package's bundled STATIC
     * model list when core-auth had not fetched a live catalog yet. This library has no
     * {@code repos/} layout, so the provider set is supplied by the caller and there is no
     * bundled static fallback — a provider with no cached models is simply skipped, matching
     * the JS behavior for a provider with neither a cache entry nor a static list.
     */
    public static List<CatalogEntry> catalogEntries(Store store, JsonCodec json, List<String> providerIds) {
        List<CatalogEntry> out = new ArrayList<>();
        if (providerIds == null || providerIds.isEmpty()) return out;
        ModelsCache cache = new ModelsCache(store, json);
        for (String provider : providerIds) {
            ModelsCache.Entry entry = cache.read(provider);
            if (entry == null || entry.models == null) continue;
            List<String> order = (entry.ranking != null && !entry.ranking.isEmpty())
                    ? entry.ranking
                    : new ArrayList<>(entry.models.keySet());
            Map<String, Object> scores = entry.scores != null ? entry.scores : Collections.emptyMap();
            for (String model : order) {
                Object raw = entry.models.get(model);
                if (raw == null) continue;
                Map<?, ?> modelObj = raw instanceof Map ? (Map<?, ?>) raw : null;
                String name = model;
                Integer contextLimit = null;
                Integer outputLimit = null;
                if (modelObj != null) {
                    Object n = modelObj.get("name");
                    if (n instanceof String && !((String) n).isEmpty()) name = (String) n;
                    Object limit = modelObj.get("limit");
                    if (limit instanceof Map) {
                        Object ctx = ((Map<?, ?>) limit).get("context");
                        Object outp = ((Map<?, ?>) limit).get("output");
                        if (ctx instanceof Number) contextLimit = ((Number) ctx).intValue();
                        if (outp instanceof Number) outputLimit = ((Number) outp).intValue();
                    }
                }
                Double score = null;
                Object sc = scores.get(model);
                if (sc instanceof Number) score = ((Number) sc).doubleValue();
                out.add(new CatalogEntry(provider, model, name, score, contextLimit, outputLimit));
            }
        }
        return out;
    }

    // All provider ids present in the models cache store entry, used internally by
    // resolveModelMap to build its catalog without requiring a caller-supplied provider
    // list — the practical equivalent of the JS repos-scan universe is "every provider
    // core-auth has ever fetched a catalog for".
    private static List<String> cachedProviderIds(Store store, JsonCodec json) {
        for (String key : MODEL_CACHE_KEYS) {
            try {
                String raw = store.get(key);
                if (raw != null) {
                    Object all = json.parse(raw);
                    if (all instanceof Map) {
                        List<String> ids = new ArrayList<>();
                        for (Object k : ((Map<?, ?>) all).keySet()) {
                            if (k instanceof String) ids.add((String) k);
                        }
                        return ids;
                    }
                }
            } catch (Exception ignored) {
                // swallow-all; try the next legacy key
            }
        }
        return Collections.emptyList();
    }

    // -- chain normalization --------------------------------------------------

    /**
     * Normalize a stored slot value into an ordered chain: legacy single {provider,model}
     * -&gt; [obj]; a list stays a list; anything else -&gt; []. First entry is the primary,
     * the rest are ordered fallbacks. Entries missing provider/model are filtered out. Java
     * port of the JS {@code normalizeChain}.
     */
    public static List<Assignment> normalizeChain(Object raw) {
        List<Assignment> out = new ArrayList<>();
        if (raw == null) return out;
        List<?> arr = raw instanceof List ? (List<?>) raw : Collections.singletonList(raw);
        for (Object o : arr) {
            Assignment a = toAssignment(o);
            if (a != null) out.add(a);
        }
        return out;
    }

    // Accepts either a plain JSON-shaped map (the common case: stored slots come from
    // JsonCodec-parsed JSON) or an already-typed Assignment (for programmatic callers).
    private static Assignment toAssignment(Object o) {
        if (o == null) return null;
        String provider;
        String model;
        String name;
        boolean derived;
        if (o instanceof Assignment) {
            Assignment src = (Assignment) o;
            provider = src.provider;
            model = src.model;
            name = src.name;
            derived = src.derived;
        } else if (o instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) o;
            Object pv = m.get("provider");
            Object mv = m.get("model");
            Object nv = m.get("name");
            provider = pv instanceof String ? (String) pv : null;
            model = mv instanceof String ? (String) mv : null;
            name = nv instanceof String ? (String) nv : null;
            derived = Boolean.TRUE.equals(m.get("derived"));
        } else {
            return null;
        }
        if (provider == null || provider.isEmpty() || model == null || model.isEmpty()) return null;
        return new Assignment(provider, model, name, derived);
    }

    // -- heal/derive --------------------------------------------------------------

    /**
     * Effective tier -&gt; ORDERED CHAIN of {provider, model, name, derived}. Each stored
     * entry is kept while its model still exists in the catalog; a fully stale tier heals
     * ONLY within the provider the user chose — never silently to a different provider (an
     * Opus-&gt;antigravity mapping must not become the tier-source provider). When the
     * chosen provider has no catalog at all, the stored entry passes through untouched.
     * Only a tier with NO stored choice derives from the whole catalog. "-auto" ids are
     * skipped. Faithful port of the JS {@code resolveModelMap}; a {@code default} key is
     * always present.
     */
    public static Map<String, List<Assignment>> resolveModelMap(Store store, JsonCodec json, RoutingProfile p) {
        Map<String, Object> stored = readModelMap(store, json, p);
        List<CatalogEntry> catalog = new ArrayList<>();
        for (CatalogEntry e : catalogEntries(store, json, cachedProviderIds(store, json))) {
            if (!e.model.endsWith("-auto")) catalog.add(e);
        }

        Map<String, List<Assignment>> eff = new LinkedHashMap<>();
        List<String> tiers = resolveTiers(store, json, p);
        for (String tier : tiers) {
            eff.put(tier, pick(catalog, stored, p, tier, tier));
        }

        List<Assignment> dflt = pick(catalog, stored, p, "default", null);
        String first = null;
        for (String t : tiers) {
            if (!eff.get(t).isEmpty()) {
                first = t;
                break;
            }
        }
        if (!dflt.isEmpty()) {
            eff.put("default", dflt);
        } else if (first != null) {
            List<Assignment> derivedDefault = new ArrayList<>();
            for (Assignment a : eff.get(first)) {
                derivedDefault.add(new Assignment(a.provider, a.model, a.name, true));
            }
            eff.put("default", derivedDefault);
        } else {
            eff.put("default", new ArrayList<>());
        }
        return eff;
    }

    private static boolean has(List<CatalogEntry> catalog, String provider, String model) {
        for (CatalogEntry e : catalog) {
            if (e.provider.equals(provider) && e.model.equals(model)) return true;
        }
        return false;
    }

    private static String nameOf(List<CatalogEntry> catalog, String provider, String model) {
        for (CatalogEntry e : catalog) {
            if (e.provider.equals(provider) && e.model.equals(model)) {
                return e.name != null ? e.name : model;
            }
        }
        return model;
    }

    private static boolean providerKnown(List<CatalogEntry> catalog, String provider) {
        for (CatalogEntry e : catalog) {
            if (e.provider.equals(provider)) return true;
        }
        return false;
    }

    private static CatalogEntry deriveIn(List<CatalogEntry> entries, String keyword) {
        if (keyword == null || keyword.isEmpty()) return null;
        String kw = keyword.toLowerCase(Locale.ROOT);
        for (CatalogEntry e : entries) {
            if (e.model.toLowerCase(Locale.ROOT).contains(kw)) return e;
        }
        return null;
    }

    private static List<CatalogEntry> byProvider(List<CatalogEntry> catalog, String provider) {
        List<CatalogEntry> out = new ArrayList<>();
        for (CatalogEntry e : catalog) {
            if (e.provider.equals(provider)) out.add(e);
        }
        return out;
    }

    private static List<Assignment> pick(List<CatalogEntry> catalog, Map<String, Object> stored,
                                          RoutingProfile p, String slot, String keyword) {
        List<Assignment> chain = normalizeChain(stored.get(slot));
        List<Assignment> out = new ArrayList<>();
        for (Assignment e : chain) {
            if (has(catalog, e.provider, e.model)) {
                out.add(new Assignment(e.provider, e.model, nameOf(catalog, e.provider, e.model), false));
            } else if (!providerKnown(catalog, e.provider)) {
                out.add(new Assignment(e.provider, e.model, e.model, false));
            }
        }
        if (!out.isEmpty()) return out;

        // Whole chain stale — heal WITHIN the chosen provider (only its model id changed);
        // cross-provider derivation is reserved for unset tiers, preferring the
        // tier-source provider (the app's own models are the natural default).
        String preferred = chain.isEmpty() ? null : chain.get(0).provider;
        if (preferred != null) {
            CatalogEntry d = deriveIn(byProvider(catalog, preferred), keyword);
            List<Assignment> r = new ArrayList<>();
            if (d != null) r.add(new Assignment(d.provider, d.model, nameOf(catalog, d.provider, d.model), true));
            return r;
        }
        CatalogEntry d = deriveIn(byProvider(catalog, p.tierSourceProvider), keyword);
        if (d == null) d = deriveIn(catalog, keyword);
        List<Assignment> r = new ArrayList<>();
        if (d != null) r.add(new Assignment(d.provider, d.model, nameOf(catalog, d.provider, d.model), true));
        return r;
    }

    // -- env pairs --------------------------------------------------------------

    /**
     * {key,value} env pairs the wrapper exports so the app's /model shows the mapped
     * models as custom tier entries (real names via *_NAME) and uses the default tier as
     * the session default. Java port of the JS {@code modelEnvPairs}.
     */
    public static List<KV> modelEnvPairs(Store store, JsonCodec json, RoutingProfile p) {
        Map<String, List<Assignment>> eff = resolveModelMap(store, json, p);
        List<KV> pairs = new ArrayList<>();
        for (Map.Entry<String, List<Assignment>> entry : eff.entrySet()) {
            String tier = entry.getKey();
            if ("default".equals(tier)) continue;
            List<Assignment> chain = entry.getValue();
            Assignment primary = chain != null && !chain.isEmpty() ? chain.get(0) : null; // tier's primary drives /model display
            if (primary == null || primary.model == null || primary.model.isEmpty()) continue;
            String upper = tier.toUpperCase(Locale.ROOT); // e.g. fable -> ..._DEFAULT_FABLE_MODEL
            pairs.add(new KV(p.envPrefix + "_DEFAULT_" + upper + "_MODEL", primary.model));
            pairs.add(new KV(p.envPrefix + "_DEFAULT_" + upper + "_MODEL_NAME",
                    primary.name != null ? primary.name : primary.model));
        }
        List<Assignment> dflt = eff.get("default");
        if (dflt != null && !dflt.isEmpty()) {
            Assignment d = dflt.get(0);
            if (d.model != null && !d.model.isEmpty()) {
                pairs.add(new KV(p.envPrefix + "_MODEL", d.model));
            }
        }
        return pairs;
    }
}
