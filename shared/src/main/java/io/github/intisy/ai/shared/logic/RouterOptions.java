package io.github.intisy.ai.shared.logic;

import io.github.intisy.ai.shared.routing.HandlerResolver;
import io.github.intisy.ai.shared.routing.RoutingProfile;
import io.github.intisy.ai.shared.spi.Clock;
import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.Logger;
import io.github.intisy.ai.shared.spi.Store;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * Options for {@link Router#route}. Java analog of the old JVM-only {@code ProxyOptions},
 * minus the transport concerns (port/listen) — the caller (server daemon, TeaVM host, test)
 * owns the actual HTTP transport; {@link Router} only resolves a request to a response.
 */
public class RouterOptions {
    public RoutingProfile profile;
    public HandlerResolver resolveHandler;
    public Store store;
    public JsonCodec json;
    public Clock clock;
    public Logger log;
    /** Callback for user-visible notices (heal/fallback/exhaustion). Not a JSONL file writer —
     *  the JVM side supplies that behavior via its own {@link Notifier} implementation. */
    public Notifier notify;
    /** Supplies the provider ids the {@code /v1/models} catalog and model-recovery lookups
     *  should scan (the caller's registered handlers), read fresh on every request. */
    public Supplier<List<String>> listProviders = Collections::emptyList;
    /** Passed through to {@code HandlerCtx.configDir}; Router carries no filesystem notion
     *  of its own, so this is just an opaque string threaded to the handler. */
    public String configDir = "";
}
