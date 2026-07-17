package io.github.intisy.ai.exampleserver;

import io.github.intisy.ai.shared.routing.RateLimitInfo;
import io.github.intisy.ai.shared.routing.RoutingProfile;
import io.github.intisy.ai.shared.spi.http.HttpResponse;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The claude-code (Anthropic) {@link RoutingProfile} the example-server can host a proxy for.
 * Java port of the TS {@code anthropicProfile()} (see {@code libs/claude-code-proxy/src/
 * profiles/anthropic.ts}) — same tier detection, env naming, defaults, and Anthropic-shaped
 * synthesized 429.
 */
public final class AppProfiles {
    private static final Pattern TIER_REGEX = Pattern.compile("^claude-([a-z]+)-\\d");
    private static final Pattern NATIVE_MODEL = Pattern.compile("^claude-");

    private AppProfiles() {
    }

    public static List<String> apps() {
        return Arrays.asList("claude-code");
    }

    public static RoutingProfile byApp(String app) {
        if ("claude-code".equals(app)) return anthropic();
        throw new IllegalArgumentException("unknown app: " + app);
    }

    public static String profileName(String app) {
        if ("claude-code".equals(app)) return "anthropic";
        throw new IllegalArgumentException("unknown app: " + app);
    }

    public static RoutingProfile anthropic() {
        return base("claude-code-loader.json", "claude-code", "ANTHROPIC");
    }

    private static RoutingProfile base(String configFile, String tierSource, String envPrefix) {
        RoutingProfile p = new RoutingProfile();
        p.configFile = configFile;
        p.routingKey = "providerRouting";
        p.tierSourceProvider = tierSource;
        p.tierOrder = Arrays.asList("opus", "sonnet", "haiku", "fable");
        p.tierFallback = Arrays.asList("opus", "sonnet", "haiku");
        p.tierRegex = TIER_REGEX;
        p.nativeModelPattern = NATIVE_MODEL;
        p.envPrefix = envPrefix;
        p.defaultContext = 200000;
        p.defaultOutput = 64000;
        p.nativeRateLimit = AppProfiles::synthesize429;
        return p;
    }

    // Faithful port of the shared nativeRateLimit in anthropic.ts: copy an upstream
    // 429's headers, reconcile the reset from anthropic-ratelimit-* headers, recompute retry-after
    // from wall-clock, and emit an Anthropic-shaped rate_limit_error. The absolute-time wording in
    // the message is locale/TZ-dependent (like the TS toLocaleTimeString) and intentionally not
    // asserted by tests.
    private static RoutingProfile.Synth synthesize429(RateLimitInfo info) {
        HttpResponse upstream = info != null ? info.upstream : null;
        long reset = info != null && info.resetMs > 0 ? info.resetMs : 0;
        Map<String, String> headers = new LinkedHashMap<>();
        if (upstream != null && upstream.status == 429 && upstream.headers != null) {
            headers.putAll(upstream.headers);
            headers.remove("content-encoding");
            headers.remove("content-length");
            headers.remove("x-hub-rate-limited");
            headers.remove("x-hub-retry-after-ms");
            for (String k : new String[]{"anthropic-ratelimit-unified-5h-reset", "anthropic-ratelimit-unified-reset"}) {
                String s = headers.get(k);
                if (s != null) {
                    try {
                        long sec = Long.parseLong(s.trim());
                        if (sec * 1000 > reset) reset = sec * 1000;
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        long now = System.currentTimeMillis();
        String message = reset > now
                ? "You've hit your usage limit · resets at " + new SimpleDateFormat("h:mm a z").format(new Date(reset))
                : "You've hit your usage limit · try again later";
        headers.put("content-type", "application/json");
        headers.put("retry-after", String.valueOf(reset > now ? Math.round((reset - now) / 1000.0) : 60));
        headers.putIfAbsent("anthropic-ratelimit-unified-status", "rejected");
        headers.putIfAbsent("anthropic-ratelimit-unified-reset", String.valueOf((reset > 0 ? reset : now) / 1000));

        RoutingProfile.Synth synth = new RoutingProfile.Synth();
        synth.status = 429;
        synth.headers = headers;
        synth.body = "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":" + quote(message) + "}}";
        return synth;
    }

    private static String quote(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        String hex = Integer.toHexString(c);
                        sb.append("\\u");
                        for (int p = hex.length(); p < 4; p++) sb.append('0');
                        sb.append(hex);
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }
}
