package io.github.intisy.ai.shared.logic;

import io.github.intisy.ai.shared.routing.RateLimitInfo;
import io.github.intisy.ai.shared.routing.RoutingProfile;
import io.github.intisy.ai.shared.spi.http.HttpResponse;

import java.util.HashMap;
import java.util.Map;

/**
 * Rate-limit detection, reset-time computation, and native-shaped 429 synthesis.
 * Java port of {@code libs/core-proxy/src/rate-limit.ts} (post-refactor: the engine
 * has no app-specific header names -- {@link #rateLimitFinal} delegates entirely to
 * {@link RoutingProfile#nativeRateLimit} and returns its result verbatim).
 *
 * <p>Operates on the SPI {@link HttpResponse} (a plain {@code String} body) instead of
 * the old byte[]-bodied {@code AiResponse} -- the body is passed straight through with
 * no encoding/decoding.
 */
public final class RateLimit {

    private RateLimit() {
    }

    public static boolean isRateLimited(HttpResponse resp) {
        return resp.status == 429 || "1".equals(header(resp, "x-hub-rate-limited"));
    }

    /**
     * Earliest epoch-ms the response says it'll be usable again ({@code x-hub-retry-after-ms},
     * else {@code retry-after} seconds). {@code now} is caller-supplied (injectable for tests).
     */
    public static long rateLimitResetMs(HttpResponse resp, long now) {
        int xr = parseIntLenient(header(resp, "x-hub-retry-after-ms"));
        if (xr > 0) return now + xr;
        int ra = parseIntLenient(header(resp, "retry-after"));
        if (ra > 0) return now + ra * 1000L;
        return 0;
    }

    /**
     * Final response when every model in a chain is rate-limited. Delegates the actual
     * native-shaped 429 (status/headers/body) entirely to {@code profile.nativeRateLimit} --
     * each routing profile knows its own upstream's rate-limit header conventions and error
     * format. The profile is the SOLE owner of the synthesized headers; this engine does not
     * overlay anything on top of what the profile returns.
     */
    public static HttpResponse rateLimitFinal(HttpResponse lastResp, long resetMs, RoutingProfile profile) {
        RoutingProfile.Synth s = profile.nativeRateLimit.build(new RateLimitInfo(resetMs, lastResp));
        HttpResponse out = new HttpResponse();
        out.status = s.status;
        out.headers = new HashMap<>(s.headers);
        out.body = s.body;
        return out;
    }

    // Case-insensitive header lookup, mirroring the JS Headers API's case-insensitivity
    // regardless of how the underlying map's keys happen to be cased.
    private static String header(HttpResponse resp, String name) {
        if (resp == null || resp.headers == null) return null;
        String direct = resp.headers.get(name);
        if (direct != null) return direct;
        for (Map.Entry<String, String> e : resp.headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    // Mirrors JS parseInt(str, 10): parses an optional sign followed by a leading run of
    // digits and ignores trailing garbage; returns 0 (the JS NaN case, per the callers'
    // `> 0` guard) when no digits are present or the header is absent.
    private static int parseIntLenient(String s) {
        if (s == null) return 0;
        s = s.trim();
        int i = 0, n = s.length();
        if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
        int start = i;
        while (i < n && Character.isDigit(s.charAt(i))) i++;
        if (i == start) return 0;
        try {
            return Integer.parseInt(s.substring(0, i));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
