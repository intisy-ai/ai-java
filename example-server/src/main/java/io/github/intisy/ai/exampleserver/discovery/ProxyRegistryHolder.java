package io.github.intisy.ai.exampleserver.discovery;

import io.github.intisy.ai.jvm.proxy.ProxyRegistry;
import io.github.intisy.ai.shared.routing.RoutingProfile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Makes the server's {@link ProxyRegistry} swappable at runtime: a proxy installed on disk after
 * startup becomes usable without a restart via {@link #refresh(Path)}. Proxy-side mirror of
 * {@code ProviderRegistryHolder} — same volatile-swap discipline, keyed by proxy id instead of
 * provider id. Proxies don't resolve handlers, so unlike its provider counterpart this holder has
 * no {@code asHandlerResolver()}.
 */
public final class ProxyRegistryHolder {

    private volatile ProxyRegistry current;

    public ProxyRegistryHolder(ProxyRegistry initial) {
        this.current = initial;
    }

    public ProxyRegistry get() {
        return current;
    }

    public List<String> listProxyIds() {
        return current.listProxyIds();
    }

    /** {@code current.profileFor(id)}, or {@code null} if no such proxy is loaded. */
    public RoutingProfile profileFor(String id) {
        return current.profileFor(id);
    }

    /** {@code current.displayNameFor(id)}, or {@code null} if no such proxy is loaded. */
    public String displayNameFor(String id) {
        return current.displayNameFor(id);
    }

    /**
     * Rebuilds the registry from {@code proxiesDir} and swaps it into the volatile field. The
     * previous registry (and the {@link java.net.URLClassLoader} it holds open for its proxy
     * jars) is deliberately NOT closed: a request already in flight may still be routing through
     * one of its proxies, and closing the loader out from under it would risk a
     * {@link NoClassDefFoundError}. The cost is a leaked classloader per install, which is
     * acceptable for a demo server — a long-lived production variant would need a reference-counted
     * or quiesce-then-close strategy instead.
     */
    public void refresh(Path proxiesDir) {
        this.current = ProxyRegistry.fromDirectory(proxiesDir);
    }

    /**
     * Deletes the jar backing {@code proxyId} and rebuilds the registry without it. Returns
     * {@code false} (no-op) if the id isn't currently loaded. Unlike {@link #refresh}, this closes
     * the CURRENT registry before touching the jar: on Windows, {@code Files.delete} on a jar still
     * held open by a {@link java.net.URLClassLoader} fails with a sharing violation, so the loader
     * must release its file handle first. This does carry the same in-flight-request risk {@link
     * #refresh} accepts for its leaked-classloader tradeoff, just in the other direction — a request
     * already routing through this proxy when uninstall runs may fail with {@link
     * NoClassDefFoundError} — acceptable for a demo server's explicit, operator-initiated uninstall.
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
