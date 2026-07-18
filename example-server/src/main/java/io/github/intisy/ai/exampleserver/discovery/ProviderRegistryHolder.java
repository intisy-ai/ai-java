package io.github.intisy.ai.exampleserver.discovery;

import io.github.intisy.ai.jvm.provider.ProviderRegistry;
import io.github.intisy.ai.shared.routing.HandlerResolver;
import io.github.intisy.ai.shared.routing.Provider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Makes the server's {@link ProviderRegistry} swappable at runtime: a provider installed on disk
 * after startup becomes usable without a restart via {@link #refresh(Path)}. The router is wired
 * with lambdas that delegate through this holder (see {@code ServerMain}), so every request reads
 * whatever registry is current at dispatch time.
 */
public final class ProviderRegistryHolder {

    private volatile ProviderRegistry current;

    public ProviderRegistryHolder(ProviderRegistry initial) {
        this.current = initial;
    }

    public ProviderRegistry get() {
        return current;
    }

    public List<String> listProviderIds() {
        return current.listProviderIds();
    }

    public HandlerResolver asHandlerResolver() {
        return current.asHandlerResolver();
    }

    /** The discovered {@link Provider} whose id equals {@code id}, or {@code null}. */
    public Provider get(String id) {
        return current.get(id);
    }

    /** The jar file that registers {@code providerId} in the CURRENT registry, or {@code null}
     *  if no such provider is loaded. Lets a caller (e.g. {@code ManagementApi}) resolve an
     *  installed provider's on-disk jar without knowing its asset-name convention. */
    public Path jarFor(String providerId) {
        return current.jarFor(providerId);
    }

    /**
     * Rebuilds the registry from {@code providersDir} and swaps it into the volatile field. The
     * previous registry (and the {@link java.net.URLClassLoader} it holds open for its provider
     * jars) is deliberately NOT closed: a request already in flight may still be routing through
     * one of its providers, and closing the loader out from under it would risk a
     * {@link NoClassDefFoundError}. The cost is a leaked classloader per install, which is
     * acceptable for a demo server — a long-lived production variant would need a reference-counted
     * or quiesce-then-close strategy instead.
     */
    public void refresh(Path providersDir) {
        this.current = ProviderRegistry.fromDirectory(providersDir);
    }

    /**
     * Deletes the jar backing {@code providerId} and rebuilds the registry without it. Returns
     * {@code false} (no-op) if the id isn't currently loaded. Unlike {@link #refresh}, this closes
     * the CURRENT registry before touching the jar: on Windows, {@code Files.delete} on a jar still
     * held open by a {@link java.net.URLClassLoader} fails with a sharing violation, so the loader
     * must release its file handle first. This does carry the same in-flight-request risk {@link
     * #refresh} accepts for its leaked-classloader tradeoff, just in the other direction — a request
     * already routing through this provider when uninstall runs may fail with {@link
     * NoClassDefFoundError} — acceptable for a demo server's explicit, operator-initiated uninstall.
     */
    public synchronized boolean uninstall(String providerId, Path providersDir) {
        Path jar = current.jarFor(providerId);
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
        refresh(providersDir);
        return true;
    }

    /**
     * Updates an already-installed provider to {@code entry}'s latest jar: closes the CURRENT
     * registry FIRST (same Windows-safe sequence as {@link #uninstall} -- {@code Files.write} on
     * a jar still held open by a {@link java.net.URLClassLoader} fails with a sharing violation
     * on Windows), downloads {@code entry}'s jar over the old one via {@code source} (which also
     * rewrites the {@code .version} sidecar -- see {@link GithubOrgScan#download}), then rebuilds
     * the registry. Accounts live in the {@code Store}, not the jar, so they are untouched by this
     * -- only the provider's classes/jar are replaced. Returns the path written.
     */
    public synchronized Path update(ProviderSource source, ProviderSource.Entry entry, Path providersDir)
            throws IOException {
        try {
            // Close the current registry FIRST so the URLClassLoader releases the old jar's file
            // handle (on Windows, overwriting a still-open jar fails with a sharing violation).
            current.close();
        } catch (IOException e) {
            // Best-effort: proceed to overwrite anyway -- a loader that fails to close cleanly
            // still relinquishes its file handles on most platforms.
        }
        Path jar = source.download(entry, providersDir);
        refresh(providersDir);
        return jar;
    }
}
