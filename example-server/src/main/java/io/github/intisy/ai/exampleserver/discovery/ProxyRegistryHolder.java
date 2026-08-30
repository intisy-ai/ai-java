package io.github.intisy.ai.exampleserver.discovery;

import io.github.intisy.ai.jvm.plugin.Plugins;
import io.github.intisy.ai.jvm.proxy.ProxyRegistry;
import io.github.intisy.ai.shared.routing.ProxyContracts;
import io.github.intisy.ai.shared.routing.ProxyPlugin;
import io.github.intisy.ai.shared.routing.RoutingProfile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Makes the server's discovered proxies swappable at runtime: a proxy installed on disk after
 * startup becomes usable without a restart via {@link #refresh(Path)}. Proxy-side mirror of
 * {@code ProviderRegistryHolder}: same volatile-swap discipline, same registration with the
 * {@link Plugins} host, keyed by proxy id instead of provider id. Proxies don't resolve handlers,
 * so unlike its provider counterpart this holder has no {@code asHandlerResolver()}.
 *
 * @implNote The two differ in ONE deliberate way, and both javadocs argue for their own side:
 * {@link #refresh} here does NOT close the previous registry, accepting a leaked classloader per
 * install, while the provider holder does close it so a later delete cannot fail on Windows. That
 * disagreement is preserved rather than settled, because settling it changes behaviour on one side.
 */
public final class ProxyRegistryHolder {

    private final Plugins plugins;
    private volatile ProxyRegistry current;

    /**
     * @param plugins the host every discovered proxy is registered with and resolved through
     * @param initial the registry discovery starts from
     */
    public ProxyRegistryHolder(Plugins plugins, ProxyRegistry initial) {
        this.plugins = plugins;
        this.current = initial;
        registerAll();
    }

    private void registerAll() {
        for (String id : current.listProxyIds()) {
            plugins.register(ProxyContracts.APP_PROXY_ID, id, current.pluginFor(id));
        }
    }

    private void releaseAll() {
        for (String id : plugins.providerIds(ProxyContracts.APP_PROXY_ID)) {
            plugins.release(id);
        }
    }

    /** {@return the registry current at this moment} */
    public ProxyRegistry get() {
        return current;
    }

    /** {@return the id of every proxy the host currently resolves} */
    public List<String> listProxyIds() {
        return plugins.providerIds(ProxyContracts.APP_PROXY_ID);
    }

    /**
     * {@return that proxy's routing profile, or {@code null} when it is not loaded}
     *
     * @param id the proxy id to look for
     */
    public RoutingProfile profileFor(String id) {
        ProxyPlugin plugin = plugins.resolveOne(ProxyContracts.APP_PROXY_ID, id, ProxyPlugin.class);
        return plugin != null ? plugin.profile() : null;
    }

    /**
     * {@return that proxy's display name, or {@code null} when it is not loaded}
     *
     * @param id the proxy id to look for
     */
    public String displayNameFor(String id) {
        ProxyPlugin plugin = plugins.resolveOne(ProxyContracts.APP_PROXY_ID, id, ProxyPlugin.class);
        return plugin != null ? plugin.displayName() : null;
    }

    /**
     * Rebuilds the registry from {@code proxiesDir} and swaps it into the volatile field. The
     * previous registry (and the {@link java.net.URLClassLoader} it holds open for its proxy
     * jars) is deliberately NOT closed: a request already in flight may still be routing through
     * one of its proxies, and closing the loader out from under it would risk a
     * {@link NoClassDefFoundError}. The cost is a leaked classloader per install, which is
     * acceptable for a demo server (a long-lived production variant would need a reference-counted
     * or quiesce-then-close strategy instead).
     *
     * @param proxiesDir the directory to rebuild the registry from
     */
    public void refresh(Path proxiesDir) {
        releaseAll();
        this.current = ProxyRegistry.fromDirectory(proxiesDir);
        registerAll();
    }

    /**
     * Deletes the jar backing {@code proxyId} and rebuilds the registry without it. Returns
     * {@code false} (no-op) if the id isn't currently loaded. Unlike {@link #refresh}, this closes
     * the CURRENT registry before touching the jar: on Windows, {@code Files.delete} on a jar still
     * held open by a {@link java.net.URLClassLoader} fails with a sharing violation, so the loader
     * must release its file handle first. This does carry the same in-flight-request risk {@link
     * #refresh} accepts for its leaked-classloader tradeoff, just in the other direction: a request
     * already routing through this proxy when uninstall runs may fail with {@link
     * NoClassDefFoundError}, acceptable for a demo server's explicit, operator-initiated uninstall.
     *
     * @param proxyId the proxy to uninstall
     * @param proxiesDir the directory the registry is rebuilt from afterwards
     * @return whether the proxy was loaded at all; false is a no-op
     */
    public synchronized boolean uninstall(String proxyId, Path proxiesDir) {
        Path jar = current.jarFor(proxyId);
        if (jar == null) return false;
        try {
            // Close the current registry FIRST so the URLClassLoader releases the jar's file
            // handle (on Windows, Files.delete on a still-open jar fails with a sharing violation).
            current.close();
        } catch (IOException e) {
            // Best-effort: proceed to delete anyway -- a loader that fails to close cleanly still
            // relinquishes its file handles on most platforms.
        }
        try {
            Files.deleteIfExists(jar);
        } catch (IOException e) {
            // Log + continue to refresh; a leftover jar will simply reappear on next refresh.
        }
        refresh(proxiesDir);
        return true;
    }
}
