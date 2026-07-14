package io.github.intisy.ai.examples.support;

import io.github.intisy.ai.shared.routing.RoutingProfile;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Builds the {@link RoutingProfile}s the demos route against. A profile is the single object that
 * parameterizes the whole routing engine — tier keywords, how a native model id maps to a tier
 * (via {@code tierRegex}), and how to synthesize a native-shaped 429 when every model in a tier is
 * exhausted. Centralizing it here keeps the demos readable and lets the integration tests route
 * against the exact same fixture the demos print.
 */
public final class DemoProfiles {

    private DemoProfiles() {
    }

    /**
     * A realistic multi-tier profile: {@code opus}/{@code sonnet}/{@code haiku}, mapping any
     * {@code claude-<tier>-N} model id onto the matching tier. Its {@code nativeRateLimit} builds an
     * Anthropic-shaped {@code rate_limit_error} body — what a client sees when the whole tier is
     * rate-limited. The store's model map (see {@link DemoSeeds}) decides which providers back each
     * tier; this profile only describes the tier vocabulary and the exhaustion response.
     */
    public static RoutingProfile multiTier(String configFile, String tierSourceProvider) {
        RoutingProfile profile = new RoutingProfile();
        profile.configFile = configFile;
        profile.routingKey = "providerRouting";
        profile.tierSourceProvider = tierSourceProvider;
        List<String> tiers = Arrays.asList("opus", "sonnet", "haiku");
        profile.tierOrder = tiers;
        profile.tierFallback = tiers;
        profile.tierRegex = Pattern.compile("^claude-([a-z]+)-\\d");
        profile.envPrefix = "ANTHROPIC";
        profile.defaultContext = 200000;
        profile.defaultOutput = 64000;
        profile.nativeRateLimit = info -> {
            RoutingProfile.Synth synth = new RoutingProfile.Synth();
            synth.status = 429;
            synth.headers = new HashMap<>();
            synth.headers.put("content-type", "application/json");
            synth.body = "{\"type\":\"error\","
                    + "\"error\":{\"type\":\"rate_limit_error\","
                    + "\"message\":\"all models for this tier are rate limited\"}}";
            return synth;
        };
        return profile;
    }
}
