package io.github.intisy.ai.exampleserver.admin;

import io.github.intisy.ai.exampleserver.discovery.ProviderRegistryHolder;
import io.github.intisy.ai.jvm.backend.store.FileStore;
import io.github.intisy.ai.shared.routing.ConfigField;
import io.github.intisy.ai.shared.routing.ConfigGroup;
import io.github.intisy.ai.shared.routing.ConfigSchema;
import io.github.intisy.ai.shared.routing.ConfigurableProvider;
import io.github.intisy.ai.shared.routing.HandlerCtx;
import io.github.intisy.ai.shared.routing.Provider;
import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.Logger;
import io.github.intisy.ai.shared.spi.Store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * UI-safe provider-config administration: resolves an installed provider and, if it implements the
 * {@link ConfigurableProvider} capability, calls its typed config methods directly (no fabricated
 * HTTP request, no JSON round-trip). Mirrors {@link QuotaAdmin}'s shape (encapsulates the
 * {@link Store}; {@code ManagementApi} never sees it directly). A provider that does not implement
 * the capability answers {@code null} to {@link #getConfig}, so the dashboard can hide the config
 * card instead of showing an error.
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

    /** {@code {groups,values}}, or {@code null} if the provider has no config surface. */
    public Map<String, Object> getConfig(String providerId) {
        ConfigurableProvider cp = resolveConfigurable(providerId);
        if (cp == null) return null;

        HandlerCtx ctx = new HandlerCtx(configDir, store, log, null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("groups", groupsToWire(cp.configSchema(ctx)));
        result.put("values", cp.getConfigValues(ctx));
        return result;
    }

    /** Persists {@code values} via the provider and returns its re-read {@code {values}}. */
    public Map<String, Object> putConfig(String providerId, Map<String, Object> values) {
        Provider p = holder.get(providerId);
        if (p == null) {
            throw new IllegalArgumentException("unknown provider: " + providerId);
        }
        if (!(p instanceof ConfigurableProvider)) {
            throw new IllegalArgumentException("provider has no config surface: " + providerId);
        }

        HandlerCtx ctx = new HandlerCtx(configDir, store, log, null);
        Map<String, Object> reread = ((ConfigurableProvider) p).putConfigValues(ctx, values);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("values", reread);
        return result;
    }

    private ConfigurableProvider resolveConfigurable(String providerId) {
        Provider p = holder.get(providerId);
        if (p == null) {
            throw new IllegalArgumentException("unknown provider: " + providerId);
        }
        return p instanceof ConfigurableProvider ? (ConfigurableProvider) p : null;
    }

    // Maps the typed ConfigSchema into the wire shape's groups[] array, omitting the
    // options/defaultValue keys entirely when null (matching the existing wire shape, which never
    // carried them for fields without options/a default).
    private static List<Map<String, Object>> groupsToWire(ConfigSchema schema) {
        List<Map<String, Object>> groups = new ArrayList<>();
        if (schema == null || schema.groups == null) return groups;
        for (ConfigGroup group : schema.groups) {
            Map<String, Object> groupMap = new LinkedHashMap<>();
            groupMap.put("title", group.title);
            List<Map<String, Object>> fields = new ArrayList<>();
            if (group.fields != null) {
                for (ConfigField field : group.fields) {
                    fields.add(fieldToWire(field));
                }
            }
            groupMap.put("fields", fields);
            groups.add(groupMap);
        }
        return groups;
    }

    private static Map<String, Object> fieldToWire(ConfigField field) {
        Map<String, Object> fieldMap = new LinkedHashMap<>();
        fieldMap.put("key", field.key);
        fieldMap.put("label", field.label);
        fieldMap.put("type", field.type);
        if (field.options != null) fieldMap.put("options", field.options);
        // Wire key is "default" (not "defaultValue" -- see ConfigField's javadoc: the Java field is
        // renamed only because "default" is a reserved word).
        if (field.defaultValue != null) fieldMap.put("default", field.defaultValue);
        return fieldMap;
    }
}
