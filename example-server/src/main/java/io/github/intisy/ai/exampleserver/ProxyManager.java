package io.github.intisy.ai.exampleserver;

import io.github.intisy.ai.exampleserver.discovery.ProviderRegistryHolder;
import io.github.intisy.ai.jvm.AiJava;
import io.github.intisy.ai.shared.routing.RoutingProfile;
import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.Logger;
import io.github.intisy.ai.shared.spi.Store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs and manages an in-JVM routing proxy per app (see {@link AppProfiles}). Each running proxy is
 * a {@link ProxyServer} bound to its own loopback port, backed by a {@link AiJava.WiredRouter} for
 * that app's {@link RoutingProfile} and the shared {@link ProviderRegistryHolder}. Definitions
 * {@code {port, enabled}} persist under {@code proxies.json}; enabled proxies auto-start on boot.
 * Start failures (e.g. port in use) are captured as an error status, never thrown out to crash the
 * host server.
 */
public final class ProxyManager {
    private static final String STORE_KEY = "proxies.json";
    private static final Map<String, Integer> DEFAULT_PORTS = new LinkedHashMap<>();

    static {
        DEFAULT_PORTS.put("claude-code", 34567);
    }

    private final AiJava ai;
    private final ProviderRegistryHolder holder;
    private final Store store;
    private final JsonCodec json;
    private final Logger log;
    private final Map<String, ProxyServer> running = new ConcurrentHashMap<>();
    private final Map<String, String> lastError = new ConcurrentHashMap<>();

    public ProxyManager(AiJava ai, ProviderRegistryHolder holder, Store store, JsonCodec json, Logger log) {
        this.ai = ai;
        this.holder = holder;
        this.store = store;
        this.json = json;
        this.log = log;
    }

    public List<ProxyStatus> list() {
        List<ProxyStatus> out = new ArrayList<>();
        Map<String, Object> defs = readDefs();
        for (String app : AppProfiles.apps()) {
            out.add(status(app, defs, running.get(app) != null ? null : lastError.get(app)));
        }
        return out;
    }

    public ProxyStatus setPort(String app, int port) {
        AppProfiles.byApp(app); // validate
        Map<String, Object> defs = readDefs();
        Map<String, Object> def = def(defs, app);
        def.put("port", port);
        defs.put(app, def);
        writeDefs(defs);
        return status(app, defs, lastError.get(app));
    }

    public ProxyStatus start(String app) {
        RoutingProfile profile = AppProfiles.byApp(app); // throws on unknown
        Map<String, Object> defs = readDefs();
        int port = portOf(defs, app);

        ProxyServer existing = running.get(app);
        if (existing != null) {
            if (existing.port() == port) return status(app, defs, null); // already running, same port
            stop(app); // port changed -> restart
        }

        AiJava.WiredRouter router = ai.router(profile,
                id -> holder.asHandlerResolver().resolve(id), holder::listProviderIds);
        try {
            ProxyServer server = ProxyServer.start(router, port);
            running.put(app, server);
            lastError.remove(app);
            Map<String, Object> def = def(defs, app);
            def.put("port", server.port());
            def.put("enabled", true);
            defs.put(app, def);
            writeDefs(defs);
            return status(app, defs, null);
        } catch (RuntimeException e) {
            lastError.put(app, e.getMessage() != null ? e.getMessage() : "start failed");
            if (log != null) log.log("proxy start failed for " + app + ": " + e.getMessage());
            return status(app, defs, lastError.get(app));
        }
    }

    public ProxyStatus stop(String app) {
        AppProfiles.byApp(app); // validate
        ProxyServer server = running.remove(app);
        if (server != null) server.stop();
        lastError.remove(app);
        Map<String, Object> defs = readDefs();
        Map<String, Object> def = def(defs, app);
        def.put("enabled", false);
        defs.put(app, def);
        writeDefs(defs);
        return status(app, defs, null);
    }

    public void startEnabledOnBoot() {
        Map<String, Object> defs = readDefs();
        for (String app : AppProfiles.apps()) {
            Object def = defs.get(app);
            if (def instanceof Map && Boolean.TRUE.equals(((Map<?, ?>) def).get("enabled"))) {
                start(app); // best-effort; errors captured in status
            }
        }
    }

    public void stopAll() {
        for (ProxyServer s : running.values()) {
            try {
                s.stop();
            } catch (RuntimeException ignored) {
            }
        }
        running.clear();
    }

    private ProxyStatus status(String app, Map<String, Object> defs, String error) {
        ProxyStatus s = new ProxyStatus();
        s.app = app;
        s.profile = AppProfiles.profileName(app);
        s.port = portOf(defs, app);
        s.running = running.get(app) != null;
        s.error = error;
        return s;
    }

    private int portOf(Map<String, Object> defs, String app) {
        Object def = defs.get(app);
        if (def instanceof Map) {
            Object p = ((Map<?, ?>) def).get("port");
            if (p instanceof Number) return ((Number) p).intValue();
        }
        Integer d = DEFAULT_PORTS.get(app);
        return d != null ? d : 0;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> def(Map<String, Object> defs, String app) {
        Object def = defs.get(app);
        return def instanceof Map ? new LinkedHashMap<>((Map<String, Object>) def) : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readDefs() {
        String raw = store.get(STORE_KEY);
        if (raw == null) return new LinkedHashMap<>();
        Object parsed = json.parse(raw);
        return parsed instanceof Map ? new LinkedHashMap<>((Map<String, Object>) parsed) : new LinkedHashMap<>();
    }

    private void writeDefs(Map<String, Object> defs) {
        store.put(STORE_KEY, json.stringify(defs));
    }

    public static final class ProxyStatus {
        public String app;
        public String profile;
        public int port;
        public boolean running;
        public String error;
    }
}
