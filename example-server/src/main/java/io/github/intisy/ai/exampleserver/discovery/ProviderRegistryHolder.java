package io.github.intisy.ai.exampleserver.discovery;

import io.github.intisy.ai.jvm.provider.ProviderRegistry;
import io.github.intisy.ai.shared.routing.HandlerResolver;

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
}
