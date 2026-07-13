package io.github.intisy.ai.shared.logic;

import io.github.intisy.ai.shared.routing.HandlerResolver;
import io.github.intisy.ai.shared.routing.ProxyHandler;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Factory methods for creating {@link HandlerResolver} implementations.
 * Java analog of {@code libs/core-proxy/src/handler-resolver.ts} (makeDynamicResolver),
 * but using a registry pattern instead of dynamic import (since Java providers are
 * compiled classes, not dynamic modules).
 */
public final class HandlerResolvers {

    private HandlerResolvers() {
    }

    /**
     * Creates a {@link HandlerResolver} that resolves handlers from a fixed registry.
     * The registry is defensively copied so external mutations do not leak in.
     *
     * @param registry a map from handler name to {@link ProxyHandler}
     * @return a resolver that looks up handlers by name
     */
    public static HandlerResolver fromRegistry(Map<String, ProxyHandler> registry) {
        // Defensive copy: copy the map so later external mutations don't leak in
        Map<String, ProxyHandler> copy = new HashMap<>(registry);
        return new HandlerResolver() {
            @Override
            public ProxyHandler resolve(String provider) {
                return copy.get(provider);
            }
        };
    }

    /**
     * Creates a {@link HandlerResolver} that resolves handlers from a dynamically
     * supplied map. The supplier is called on each {@code resolve()} call, allowing
     * the underlying registry to be mutated between calls (useful for servers that
     * register providers dynamically).
     *
     * @param supplier a function that returns a map from handler name to {@link ProxyHandler}
     * @return a resolver that re-reads from the supplier each time
     */
    public static HandlerResolver fromSupplier(Supplier<Map<String, ProxyHandler>> supplier) {
        return new HandlerResolver() {
            @Override
            public ProxyHandler resolve(String provider) {
                Map<String, ProxyHandler> current = supplier.get();
                if (current == null) return null;
                return current.get(provider);
            }
        };
    }
}
