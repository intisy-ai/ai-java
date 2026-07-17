package io.github.intisy.ai.exampleserver;

import io.github.intisy.ai.shared.routing.RateLimitInfo;
import io.github.intisy.ai.shared.routing.RoutingProfile;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppProfilesTest {

    @Test
    void anthropicProfileMatchesTs() {
        RoutingProfile p = AppProfiles.anthropic();
        assertEquals("claude-code-loader.json", p.configFile);
        assertEquals("providerRouting", p.routingKey);
        assertEquals("claude-code", p.tierSourceProvider);
        assertEquals(Arrays.asList("opus", "sonnet", "haiku", "fable"), p.tierOrder);
        assertEquals(Arrays.asList("opus", "sonnet", "haiku"), p.tierFallback);
        assertEquals("ANTHROPIC", p.envPrefix);
        assertEquals(200000, p.defaultContext);
        assertEquals(64000, p.defaultOutput);
        assertTrue(p.tierRegex.matcher("claude-opus-4").find());
        assertTrue(p.nativeModelPattern.matcher("claude-opus-4").find());
        assertTrue(RoutingProfile.isValid(p));
    }

    @Test
    void nativeRateLimitSynthesizesAnthropicShaped429() {
        RoutingProfile p = AppProfiles.anthropic();
        RoutingProfile.Synth synth = p.nativeRateLimit.build(new RateLimitInfo(0L, null));
        assertEquals(429, synth.status);
        assertTrue(synth.body.contains("\"type\":\"rate_limit_error\""), synth.body);
        assertEquals("application/json", synth.headers.get("content-type"));
        assertNotNull(synth.headers.get("retry-after"));
        assertEquals("rejected", synth.headers.get("anthropic-ratelimit-unified-status"));
    }

    @Test
    void byAppAndAppsAndUnknown() {
        assertEquals(Arrays.asList("claude-code"), AppProfiles.apps());
        assertEquals("claude-code", AppProfiles.byApp("claude-code").tierSourceProvider);
        assertThrows(IllegalArgumentException.class, () -> AppProfiles.byApp("opencode"));
        assertThrows(IllegalArgumentException.class, () -> AppProfiles.byApp("nope"));
    }
}
