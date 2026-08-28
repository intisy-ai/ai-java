package io.github.intisy.ai.exampleserver;

import io.github.intisy.ai.exampleserver.discovery.ProviderRegistryHolder;
import io.github.intisy.ai.exampleserver.discovery.ProxyRegistryHolder;
import io.github.intisy.ai.jvm.AiJava;
import io.github.intisy.ai.shared.routing.RoutingProfile;
import io.github.intisy.ai.api.seam.JsonCodec;
import io.github.intisy.ai.api.seam.Logger;
import io.github.intisy.ai.api.seam.Store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs and manages an in-JVM routing proxy per INSTALLED {@code ProxyPlugin} (discovered via
 * {@link ProxyRegistryHolder}: see the plugin's own {@code META-INF/services} registration, not a
 * hardcoded app list). Each running proxy is a {@link ProxyServer} bound to its own loopback port,
 * backed by a {@link AiJava.WiredRouter} for that plugin's {@link RoutingProfile} and the shared,
 * installed-PROVIDER {@link ProviderRegistryHolder} (proxies route through providers; the two
 * registries are independent). Definitions {@code {port, enabled}} persist under
 * {@code proxies.json}; enabled proxies auto-start on boot. Start failures (e.g. port in use, or a
 * plugin with no routing profile) are captured as an error status, never thrown out to crash the
 * host server.
 */
public final class ProxyManager {
    private static final String STORE_KEY = "proxies.json";
    private static final int DEFAULT_PORT = 34567;

    private final AiJava ai;
    private final ProviderRegistryHolder providerHolder;
    private final ProxyRegistryHolder proxyHolder;
    private final Store store;
    private final JsonCodec json;
    private final Logger log;
    private final Map<String, ProxyServer> running = new ConcurrentHashMap<>();
    private final Map<String, String> lastError = new ConcurrentHashMap<>();

    /**
     * @param ai the assembly a started proxy routes through
     * @param providerHolder resolves the providers a started proxy may choose from
     * @param proxyHolder resolves an installed proxy's own routing profile
     * @param store where the per-proxy port and enabled flag live
     * @param json the codec that document is read and written with
     * @param log where a failed start is reported
     */
    public ProxyManager(AiJava ai, ProviderRegistryHolder providerHolder, ProxyRegistryHolder proxyHolder,
                         Store store, JsonCodec json, Logger log) {
        this.ai = ai;
        this.providerHolder = providerHolder;
        this.proxyHolder = proxyHolder;
        this.store = store;
        this.json = json;
        this.log = log;
    }

    /** {@return the status of every installed proxy, running or not} */
    public List<ProxyStatus> list() {
        List<ProxyStatus> out = new ArrayList<>();
        Map<String, Object> defs = readDefs();
        for (String id : proxyHolder.listProxyIds()) {
            out.add(status(id, defs, running.get(id) != null ? null : lastError.get(id)));
        }
        return out;
    }

    /**
     * {@return that proxy's status after the change}
     *
     * @param id the proxy to change
     * @param port the port it should listen on next time it starts
     */
    public ProxyStatus setPort(String id, int port) {
        requireInstalled(id);
        Map<String, Object> defs = readDefs();
        Map<String, Object> def = def(defs, id);
        def.put("port", port);
        defs.put(id, def);
        writeDefs(defs);
        return status(id, defs, lastError.get(id));
    }

    /**
     * {@return that proxy's status, carrying the reason when the start failed}
     *
     * @param id the proxy to start
     */
    public ProxyStatus start(String id) {
        requireInstalled(id);
        RoutingProfile profile = proxyHolder.profileFor(id);
        Map<String, Object> defs = readDefs();
        if (profile == null) {
            // Guard, not the expected path: real proxy plugins declare a profile. Capture as a
            // status error rather than throwing, matching the "never crash the host" contract.
            lastError.put(id, "proxy declares no routing profile");
            return status(id, defs, lastError.get(id));
        }
        int port = portOf(defs, id);

        ProxyServer existing = running.get(id);
        if (existing != null) {
            if (existing.port() == port) return status(id, defs, null); // already running, same port
            stop(id); // port changed -> restart
        }

        AiJava.WiredRouter router = ai.router(profile,
                i -> providerHolder.asHandlerResolver().resolve(i), providerHolder::listProviderIds);
        try {
            ProxyServer server = ProxyServer.start(router, port);
            running.put(id, server);
            lastError.remove(id);
            Map<String, Object> def = def(defs, id);
            def.put("port", server.port());
            def.put("enabled", true);
            defs.put(id, def);
            writeDefs(defs);
            return status(id, defs, null);
        } catch (RuntimeException e) {
            lastError.put(id, e.getMessage() != null ? e.getMessage() : "start failed");
            if (log != null) log.error("proxy start failed for " + id + ": " + e.getMessage());
            return status(id, defs, lastError.get(id));
        }
    }

    /**
     * {@return that proxy's status after being stopped and marked disabled}
     *
     * @param id the proxy to stop
     */
    public ProxyStatus stop(String id) {
        requireInstalled(id);
        ProxyServer server = running.remove(id);
        if (server != null) server.stop();
        lastError.remove(id);
        Map<String, Object> defs = readDefs();
        Map<String, Object> def = def(defs, id);
        def.put("enabled", false);
        defs.put(id, def);
        writeDefs(defs);
        return status(id, defs, null);
    }

    /** Starts every proxy marked enabled, best-effort: a failure is captured in its status. */
    public void startEnabledOnBoot() {
        Map<String, Object> defs = readDefs();
        for (String id : proxyHolder.listProxyIds()) {
            Object def = defs.get(id);
            if (def instanceof Map && Boolean.TRUE.equals(((Map<?, ?>) def).get("enabled"))) {
                start(id); // best-effort; errors captured in status
            }
        }
    }

    /** Stops every running proxy, ignoring a proxy that fails to stop cleanly. */
    public void stopAll() {
        for (ProxyServer s : running.values()) {
            try {
                s.stop();
            } catch (RuntimeException ignored) {
            }
        }
        running.clear();
    }

    private void requireInstalled(String id) {
        if (!proxyHolder.listProxyIds().contains(id)) {
            throw new IllegalArgumentException("unknown proxy: " + id);
        }
    }

    private ProxyStatus status(String id, Map<String, Object> defs, String error) {
        ProxyStatus s = new ProxyStatus();
        s.id = id;
        s.displayName = proxyHolder.displayNameFor(id);
        s.port = portOf(defs, id);
        s.running = running.get(id) != null;
        s.routing = hasTiers(proxyHolder.profileFor(id));
        s.error = error;
        return s;
    }

    private static boolean hasTiers(RoutingProfile profile) {
        return profile != null && profile.tierOrder != null && !profile.tierOrder.isEmpty();
    }

    private int portOf(Map<String, Object> defs, String id) {
        Object def = defs.get(id);
        if (def instanceof Map) {
            Object p = ((Map<?, ?>) def).get("port");
            if (p instanceof Number) return ((Number) p).intValue();
        }
        return DEFAULT_PORT;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> def(Map<String, Object> defs, String id) {
        Object def = defs.get(id);
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

    /** What the console shows for one installed proxy. */
    public static final class ProxyStatus {
        /** The proxy's id. */
        public String id;
        /** The name the proxy declares for itself. */
        public String displayName;
        /** The port it listens on, or would listen on. */
        public int port;
        /** Whether it is serving right now. */
        public boolean running;
        /** Whether it declares a routing profile, so a tier surface can be rendered for it. */
        public boolean routing;
        /** Why the last start failed, or null when nothing has failed. */
        public String error;
    }
}
