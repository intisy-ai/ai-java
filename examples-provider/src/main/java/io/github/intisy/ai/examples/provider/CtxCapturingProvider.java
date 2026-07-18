package io.github.intisy.ai.examples.provider;

import io.github.intisy.ai.shared.routing.AccountQuota;
import io.github.intisy.ai.shared.routing.ConfigSchema;
import io.github.intisy.ai.shared.routing.ConfigurableProvider;
import io.github.intisy.ai.shared.routing.HandlerCtx;
import io.github.intisy.ai.shared.routing.Provider;
import io.github.intisy.ai.shared.routing.QuotaProvider;
import io.github.intisy.ai.shared.spi.http.HttpRequest;
import io.github.intisy.ai.shared.spi.http.HttpResponse;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A test-only example {@link Provider} whose sole purpose is proving the {@link HandlerCtx#store}
 * an admin passes into a typed capability call is really the server's injected {@code Store} (see
 * the store-threading design): both {@link #quota} ({@link QuotaProvider}) and {@link
 * #putConfigValues} ({@link ConfigurableProvider}) write a fixed marker into {@code ctx.store} IFF
 * it is non-null. A test that shares the same {@code Store} instance with the admin under test can
 * then read the marker back through its own reference to prove the two are the same store, without
 * any classloader-identity concerns (the marker travels through the store's own data, not object
 * identity across the jar's {@link java.net.URLClassLoader}). Packaged in the SAME jar as
 * {@link EchoProvider}/{@link AlwaysRateLimitedProvider} (all three listed in
 * {@code META-INF/services/io.github.intisy.ai.shared.routing.Provider}).
 */
public final class CtxCapturingProvider implements Provider, QuotaProvider, ConfigurableProvider {

    /** The provider id this instance serves. */
    public static final String ID = "ctx-capture";

    /** Key written into {@code ctx.store} when a typed capability call sees a non-null store. */
    public static final String MARKER_KEY = "ctx-capture-marker";

    /** Value written under {@link #MARKER_KEY}. */
    public static final String MARKER_VALUE = "seen";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public HttpResponse handle(HttpRequest request, HandlerCtx ctx) {
        if (ctx != null && ctx.store != null) {
            ctx.store.put(MARKER_KEY, MARKER_VALUE);
        }
        HttpResponse response = new HttpResponse();
        response.status = 200;
        response.headers = new HashMap<>();
        response.headers.put("content-type", "application/json");
        response.body = "{}";
        return response;
    }

    @Override
    public List<AccountQuota> quota(HandlerCtx ctx) {
        if (ctx != null && ctx.store != null) {
            ctx.store.put(MARKER_KEY, MARKER_VALUE);
        }
        return Collections.emptyList();
    }

    @Override
    public ConfigSchema configSchema(HandlerCtx ctx) {
        return new ConfigSchema(Collections.emptyList());
    }

    @Override
    public Map<String, Object> getConfigValues(HandlerCtx ctx) {
        return Collections.emptyMap();
    }

    @Override
    public Map<String, Object> putConfigValues(HandlerCtx ctx, Map<String, Object> values) {
        if (ctx != null && ctx.store != null) {
            ctx.store.put(MARKER_KEY, MARKER_VALUE);
        }
        return Collections.emptyMap();
    }
}
