package io.github.intisy.ai.shared.logic;

import io.github.intisy.ai.shared.routing.RoutingProfile;
import io.github.intisy.ai.shared.spi.http.HttpResponse;
import org.junit.jupiter.api.Test;

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

    private static HttpResponse resp(int status, Map<String, String> headers) {
        HttpResponse r = new HttpResponse();
        r.status = status;
        r.headers = headers;
        r.body = "";
        return r;
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

    // header lookup is case-insensitive, mirroring the JS Headers API

    @Test
    void isRateLimited_hubHeaderCaseInsensitive_true() {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Hub-Rate-Limited", "1");
        assertTrue(RateLimit.isRateLimited(resp(200, headers)));
    }

    // rateLimitFinal

    @Test
    void rateLimitFinal_delegatesEntirelyToProfile() {
        HttpResponse out = RateLimit.rateLimitFinal(null, 1234, stubProfile());
        assertEquals(429, out.status);
        assertEquals("1", out.headers.get("x-test"));
        assertTrue(out.body.contains("1234"));
    }
}
