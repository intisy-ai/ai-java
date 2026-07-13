package io.github.intisy.ai.proxy;

import io.github.intisy.ai.core.routing.HandlerResolver;
import io.github.intisy.ai.core.routing.RoutingProfile;

import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Options for {@link ProxyServer#createProxyServer}. Java analog of the JS
 * {@code ProxyOptions} (see {@code libs/core-proxy/src/types.ts}).
 */
public class ProxyOptions {
    public String configDir;
    public RoutingProfile profile;
    public HandlerResolver resolveHandler;
    public int port = 34567;
    public Consumer<String> log = s -> {
    };
    /** Optional; when {@code null} a per-server {@link Notify} instance is used, appending
     *  JSONL lines to {@code <configDir>/cache/auth-notifications.jsonl}. */
    public BiConsumer<String, String> notify;
    /** Supplies the provider ids the {@code /v1/models} catalog and model-recovery lookups
     *  should scan (the caller's registered handlers), read fresh on every request. */
    public Supplier<List<String>> listProviders = Collections::emptyList;
}
