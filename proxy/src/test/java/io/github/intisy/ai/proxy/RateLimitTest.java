package io.github.intisy.ai.proxy;

import io.github.intisy.ai.core.http.AiResponse;
import io.github.intisy.ai.core.routing.RoutingProfile;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RateLimitTest {

    // Stub profile: nativeRateLimit always synthesizes the same 429 shape, echoing
    // resetMs into the body so rateLimitFinal's plumbing can be asserted.
    private static RoutingProfile stubProfile() {
        RoutingProfile p = new RoutingProfile();
        p.nativeRateLimit = info -> {
            RoutingProfile.Synth s = new RoutingProfile.Synth();
            s.status = 429;
            s.headers = new HashMap<>();
            s.headers.put("x-test", "1");
            s.body = "{\"resetMs\":" + info.resetMs + "}";
            return s;
        };
        return p;
    }

    private static AiResponse resp(int status, Map<String, String> headers) {
        return new AiResponse(status, headers, new byte[0]);
    }

    // isRateLimited

    @Test
    void isRateLimited_status429_true() {
        assertTrue(RateLimit.isRateLimited(resp(429, new HashMap<>())));
    }

    @Test
    void isRateLimited_hubHeaderOn200_true() {
        Map<String, String> headers = new HashMap<>();
        headers.put("x-hub-rate-limited", "1");
        assertTrue(RateLimit.isRateLimited(resp(200, headers)));
    }

    @Test
    void isRateLimited_plain200_false() {
        assertFalse(RateLimit.isRateLimited(resp(200, new HashMap<>())));
    }

    // rateLimitResetMs

    @Test
    void rateLimitResetMs_hubRetryAfterMs_addsMillis() {
        long now = 1_000_000L;
        Map<String, String> headers = new HashMap<>();
        headers.put("x-hub-retry-after-ms", "5000");
        assertEquals(now + 5000, RateLimit.rateLimitResetMs(resp(200, headers), now));
    }

    @Test
    void rateLimitResetMs_retryAfterSeconds_convertsToMillis() {
        long now = 1_000_000L;
        Map<String, String> headers = new HashMap<>();
        headers.put("retry-after", "2");
        assertEquals(now + 2000, RateLimit.rateLimitResetMs(resp(200, headers), now));
    }

    @Test
    void rateLimitResetMs_noHeaders_zero() {
        long now = 1_000_000L;
        assertEquals(0, RateLimit.rateLimitResetMs(resp(200, new HashMap<>()), now));
    }

    @Test
    void rateLimitResetMs_retryAfterZero_zero() {
        long now = 1_000_000L;
        Map<String, String> headers = new HashMap<>();
        headers.put("retry-after", "0");
        assertEquals(0, RateLimit.rateLimitResetMs(resp(200, headers), now));
    }

    // rateLimitFinal

    @Test
    void rateLimitFinal_delegatesEntirelyToProfile() {
        AiResponse out = RateLimit.rateLimitFinal(null, 1234, stubProfile());
        assertEquals(429, out.status);
        assertEquals("1", out.headers.get("x-test"));
        assertTrue(new String(out.body, StandardCharsets.UTF_8).contains("1234"));
    }
}
