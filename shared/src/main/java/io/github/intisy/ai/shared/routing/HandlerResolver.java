package io.github.intisy.ai.shared.routing;

/**
 * Resolves a provider name to the {@link ProxyHandler} that serves it.
 */
public interface HandlerResolver {
    /**
     * @return the handler for {@code provider}, or {@code null} when unknown.
     */
    ProxyHandler resolve(String provider);
}
