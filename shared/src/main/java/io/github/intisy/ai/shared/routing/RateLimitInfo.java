package io.github.intisy.ai.shared.routing;

import io.github.intisy.ai.shared.spi.http.HttpResponse;

/**
 * Rate-limit signal observed from an upstream response, used to synthesize a native
 * rate-limit response via {@link RoutingProfile.NativeRateLimit}.
 */
public class RateLimitInfo {
    public long resetMs;
    public HttpResponse upstream;

    public RateLimitInfo() {
    }

    public RateLimitInfo(long resetMs, HttpResponse upstream) {
        this.resetMs = resetMs;
        this.upstream = upstream;
    }
}
