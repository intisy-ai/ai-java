package io.github.intisy.ai.core.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared model-catalog cache, keyed by provider id. Java analog of
 * {@code libs/core-auth/src/models-cache.ts}: providers write their fetched/static catalog
 * here so both the on-disk file and any JS client (OpenCode merge, Claude loader Providers
 * tab) agree on one {@code config/models.json}.
 *
 * <p>On-disk shape (must match the JS store exactly): {@code {"<providerId>": {models, ranking,
 * defaultModelId, source, sorts, sortOrders, scores, scoreSource, fetchedAt}, ...}}. Unlike
 * {@link AccountStore} this has no cross-process lock — the JS source writes it unlocked too
 * (a full read-modify-write on every call, best effort).
 */
public class ModelsCache {
    private static final String DEFAULT_FILE = "models.json";
    private static final String LEGACY_FILE = "core-auth-models.json"; // pre-rename; read fallback only

    private static final Type ALL_TYPE = new TypeToken<Map<String, Entry>>() {
    }.getType();

    // Same LONG_OR_DOUBLE rationale as AccountStore.GSON: `models`/`scores` are opaque maps of
    // provider-defined JSON (context windows, per-model scores, etc.) deserialized as Object;
    // without this, Gson's default ToNumberPolicy.DOUBLE would turn whole numbers into doubles
    // and corrupt byte-compatibility with the JS core-auth cache on every write.
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .setObjectToNumberStrategy(com.google.gson.ToNumberPolicy.LONG_OR_DOUBLE)
            .create();

    private final Path configFolder;

    public ModelsCache(Path configFolder) {
        this.configFolder = configFolder;
    }

    /**
     * Per-provider cache entry. Field order matches the JS {@code writeModelCache} object
     * literal for JSON byte-compatibility.
     */
    public static class Entry {
        public Map<String, Object> models;
        public List<String> ranking;
        public String defaultModelId;
        public String source;                    // "live" | "static" | "" — fetched-now vs shipped fallback
        public List<Object> sorts;                // [{id, label}, ...], opaque
        public Map<String, List<String>> sortOrders;
        public Map<String, Object> scores;
        public String scoreSource;
        public Long fetchedAt;                    // epoch ms
    }

    private Path cachePath() {
        return configFolder.resolve(DEFAULT_FILE);
    }

    private Path legacyPath() {
        return configFolder.resolve(LEGACY_FILE);
    }

    private void ensureDir() {
        try {
            if (!Files.exists(configFolder)) Files.createDirectories(configFolder);
        } catch (IOException ignored) {
            // best-effort, mirrors JS's unguarded mkdirSync being the only failure point
        }
    }

    private Map<String, Entry> readAll() {
        for (Path p : new Path[]{cachePath(), legacyPath()}) {
            try {
                if (Files.exists(p)) {
                    String content = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                    Map<String, Entry> all = GSON.fromJson(content, ALL_TYPE);
                    if (all != null) return all;
                }
            } catch (Exception ignored) {
                // swallow-all, mirrors the JS readAll's try/catch degrading to the next path
            }
        }
        return new LinkedHashMap<>();
    }

    /**
     * Returns the provider's cached entry, or {@code null} if there is none or it has no models
     * (JS parity: {@code entry && entry.models ? entry : null}).
     */
    public Entry read(String providerId) {
        Entry entry = readAll().get(providerId);
        return entry != null && entry.models != null ? entry : null;
    }

    /**
     * Read-modify-write: merges {@code entry} into the on-disk map under {@code providerId} and
     * rewrites the whole file (JS parity: {@code writeModelCache}), dropping the legacy file once
     * the renamed one exists.
     */
    public void write(String providerId, Entry entry) {
        try {
            Map<String, Entry> all = readAll();
            if (entry.fetchedAt == null) entry.fetchedAt = 0L;
            all.put(providerId, entry);
            ensureDir();
            Files.write(cachePath(), GSON.toJson(all, ALL_TYPE).getBytes(StandardCharsets.UTF_8));
            try {
                Files.deleteIfExists(legacyPath());
            } catch (IOException ignored) {
                // best-effort cleanup of the old core- prefixed file
            }
        } catch (Exception ignored) {
            // swallow-all, mirrors the JS writeModelCache's try/catch + log-only failure
        }
    }
}
