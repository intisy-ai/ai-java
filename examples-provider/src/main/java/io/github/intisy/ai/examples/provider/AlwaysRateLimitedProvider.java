package io.github.intisy.ai.examples.provider;

import io.github.intisy.ai.shared.routing.HandlerCtx;
import io.github.intisy.ai.shared.routing.Provider;
import io.github.intisy.ai.shared.spi.http.HttpRequest;
import io.github.intisy.ai.shared.spi.http.HttpResponse;

import java.util.HashMap;

/**
 * An always-rate-limited example {@link Provider}: every request gets a {@code 429} carrying the
 * rate-limit headers the routing engine reads ({@code retry-after} and {@code x-hub-rate-limited}).
 * This is the "it's exhausted" half of the {@code :examples} showcase's fallback chain — the
 * router sees the 429, records the reset time, and advances to the next entry in the tier
 * (the {@link EchoProvider}); when a tier contains ONLY this provider, the router synthesizes a
 * native-shaped 429 instead.
 *
 * <p>Packaged in the SAME jar as {@link EchoProvider} (both listed in
 * {@code META-INF/services/io.github.intisy.ai.shared.routing.Provider}) so the example proves the
 * registry discovers multiple providers from one artifact.
 */
public final class AlwaysRateLimitedProvider implements Provider {

    /** The provider id this instance serves; matches the {@code provider} field in a model-map assignment. */
    public static final String ID = "ratelimited";

    // 30 seconds, in the two header forms the engine understands: x-hub-retry-after-ms wins when
    // present, retry-after (seconds) is the fallback -- both point at the same reset instant.
    private static final long RETRY_AFTER_MS = 30_000L;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public HttpResponse handle(HttpRequest request, HandlerCtx ctx) {
        HttpResponse response = new HttpResponse();
        response.status = 429;
        response.headers = new HashMap<>();
        response.headers.put("content-type", "application/json");
        response.headers.put("x-hub-rate-limited", "1");
        response.headers.put("x-hub-retry-after-ms", Long.toString(RETRY_AFTER_MS));
        response.headers.put("retry-after", Long.toString(RETRY_AFTER_MS / 1000L));
        response.body = "{"
                + "\"type\":\"error\","
                + "\"error\":{\"type\":\"rate_limit_error\",\"message\":\"this example provider is always rate limited\"}"
                + "}";
        return response;
    }
}
