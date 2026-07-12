package io.github.intisy.ai.core.routing;

import io.github.intisy.ai.core.http.AiResponse;

/**
 * Rate-limit signal observed from an upstream response, used to synthesize a native
 * rate-limit response via {@link RoutingProfile.NativeRateLimit}.
 */
public class RateLimitInfo {
    public long resetMs;
    public AiResponse upstream;

    public RateLimitInfo() {
    }

    public RateLimitInfo(long resetMs, AiResponse upstream) {
        this.resetMs = resetMs;
        this.upstream = upstream;
    }
}
