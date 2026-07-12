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
import java.util.Map;

/**
 * core-auth config: the active provider (and, in the JS source, harness auto-model settings —
 * not ported here). Java analog of {@code libs/core-auth/src/config.ts}.
 *
 * <p>Stored in {@code config/auth.json} (preferred), read with a fallback chain for configs
 * written by older code: the top-level {@code auth.json} (pre config/-subdir), then the
 * pre-rename {@code core-auth.json} in both locations. Writes always go to the preferred path.
 *
 * <p>{@code configFolder} is the resolved {@code config} subdirectory (same convention as
 * {@link AccountStore}/{@link ModelsCache}); the fallback/legacy paths climb one level to the
 * app's top-level config dir, mirroring the JS {@code getConfigDir()} vs {@code configFolder()}
 * distinction.
 */
public class AuthConfig {
    private static final String FILE = "auth.json";
    private static final String LEGACY_FILE = "core-auth.json";

    private static final Type CONFIG_TYPE = new TypeToken<Map<String, Object>>() {
    }.getType();

    // Config is an opaque, arbitrary JSON document (auto-model order/exclusions, etc.); use the
    // same LONG_OR_DOUBLE policy as AccountStore/ModelsCache so whole numbers stashed in it don't
    // grow a spurious ".0" and corrupt byte-compatibility with the JS core-auth config.
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .setObjectToNumberStrategy(com.google.gson.ToNumberPolicy.LONG_OR_DOUBLE)
            .create();

    private final Path configFolder;

    public AuthConfig(Path configFolder) {
        this.configFolder = configFolder;
    }

    private Path preferredPath() {
        return configFolder.resolve(FILE);
    }

    private Path fallbackPath() {
        return configFolder.getParent().resolve(FILE);
    }

    private Path[] legacyPaths() {
        return new Path[]{configFolder.resolve(LEGACY_FILE), configFolder.getParent().resolve(LEGACY_FILE)};
    }

    private void ensureDir() {
        try {
            if (!Files.exists(configFolder)) Files.createDirectories(configFolder);
        } catch (IOException ignored) {
            // best-effort, mirrors JS's unguarded mkdirSync being the only failure point
        }
    }

    /** Preferred -&gt; fallback -&gt; legacy(preferred) -&gt; legacy(fallback); first existing file wins. */
    private Map<String, Object> readConfig() {
        Path[] candidates = {preferredPath(), fallbackPath(), legacyPaths()[0], legacyPaths()[1]};
        for (Path p : candidates) {
            try {
                if (Files.exists(p)) {
                    String content = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                    Map<String, Object> cfg = GSON.fromJson(content, CONFIG_TYPE);
                    return cfg != null ? cfg : new LinkedHashMap<>();
                }
            } catch (Exception ignored) {
                // swallow-all, mirrors the JS readConfig's try/catch degrading to {}
            }
        }
        return new LinkedHashMap<>();
    }

    private void writeConfig(Map<String, Object> cfg) {
        try {
            ensureDir();
            Files.write(preferredPath(), GSON.toJson(cfg, CONFIG_TYPE).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // swallow-all, mirrors the JS writeConfig's try/catch (best-effort write)
        }
    }

    /** The active provider id, or {@code ""} if unset (JS parity: {@code readConfig().provider || ""}). */
    public String activeProvider() {
        Object provider = readConfig().get("provider");
        return provider != null ? provider.toString() : "";
    }

    /** Sets the active provider and writes it to the preferred {@code config/auth.json}. */
    public void setActiveProvider(String id) {
        Map<String, Object> cfg = readConfig();
        cfg.put("provider", id);
        writeConfig(cfg);
    }
}
