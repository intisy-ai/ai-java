package io.github.intisy.ai.shared.store;

import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.Store;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * core-auth config: the active provider (and, in the JS source, harness auto-model settings —
 * not ported here). Java analog of {@code libs/core-auth/src/config.ts}, rewired onto the
 * {@link Store} + {@link JsonCodec} SPIs. Stored under the key {@code "auth.json"} — no
 * fallback/legacy paths (those were a filesystem-directory concept; the {@code Store}
 * implementation owns key-to-location mapping now).
 */
public class AuthConfig {
    private static final String KEY = "auth.json";

    private final Store store;
    private final JsonCodec json;

    public AuthConfig(Store store, JsonCodec json) {
        this.store = store;
        this.json = json;
    }

    private Map<String, Object> readConfig() {
        String raw = store.get(KEY);
        if (raw != null) {
            try {
                Map<String, Object> cfg = JsonUtil.asMap(json.parse(raw));
                if (cfg != null) return cfg;
            } catch (Exception ignored) {
                // swallow-all, mirrors the JS readConfig's try/catch degrading to {}
            }
        }
        return new LinkedHashMap<>();
    }

    private void writeConfig(Map<String, Object> cfg) {
        store.put(KEY, json.stringify(cfg));
    }

    /** The active provider id, or {@code ""} if unset (JS parity: {@code readConfig().provider || ""}). */
    public String activeProvider() {
        Object provider = readConfig().get("provider");
        return provider != null ? provider.toString() : "";
    }

    /** Sets the active provider and writes it to {@code auth.json}. */
    public void setActiveProvider(String id) {
        Map<String, Object> cfg = readConfig();
        cfg.put("provider", id);
        writeConfig(cfg);
    }
}
