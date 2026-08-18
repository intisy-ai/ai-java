package io.github.intisy.ai.exampleserver;

import io.github.intisy.ai.ir.spi.Translator;
import io.github.intisy.ai.jvm.translator.TranslatorRegistry;
import io.github.intisy.ai.shared.routing.RoutingProfile;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The {@link RoutingProfile} the example server routes against: opus/sonnet/haiku tiers, mapping a
 * {@code claude-<tier>-N} model id onto its tier, with an Anthropic-shaped {@code rate_limit_error}
 * synthesized when a whole tier is exhausted. Mirrors the fixture the {@code :examples} demos use.
 * This is also the ONE profile factory every app-proxy fixture in this repo builds from (the
 * "claude-code" and "opencode" test fixtures in {@code RoutingApiIntegrationTest} both call
 * {@link #echoTiers}, each with their own {@code configFile}) -- both real app-proxies speak the
 * Anthropic wire format, so setting {@link RoutingProfile#translator} here activates the IR
 * front-door for both.
 */
public final class ServerProfile {

    private static Translator translator;

    private ServerProfile() {
    }

    public static RoutingProfile echoTiers(String configFile) {
        RoutingProfile profile = new RoutingProfile();
        profile.configFile = configFile;
        profile.routingKey = "providerRouting";
        profile.tierSourceProvider = "echo";
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
        // Both app fixtures built from this profile speak the Anthropic wire format, so the SAME
        // translator activates the IR front-door for either: Router prefers a resolved handler's
        // handleIr whenever the profile also carries a translator, falling back to legacy handle()
        // otherwise (see core-proxy's Router#route).
        profile.translator = translator();
        return profile;
    }

    /**
     * @implNote Cached after the first successful load: {@link TranslatorRegistry#load} keeps
     * ONE static classloader open and closes the previous one on every call, so re-scanning on
     * every {@code echoTiers} call would strand a translator already handed to an earlier profile
     * the moment a later call replaces the loader -- any class it still needs to define lazily
     * (e.g. an anonymous {@code StreamDecoder}) would then fail with {@link NoClassDefFoundError}.
     * Fails fast rather than leaving {@link RoutingProfile#translator} {@code null}: a null
     * translator would silently skip the IR front door for every request built from this profile
     * instead of surfacing the missing jar.
     */
    private static synchronized Translator translator() {
        if (translator == null) {
            File dir = new File(System.getProperty("exampleserver.translatorsDir", "translators"));
            List<Translator> found = TranslatorRegistry.load(dir);
            if (found.isEmpty()) {
                throw new IllegalStateException(
                        "no Translator implementation found in " + dir.getAbsolutePath()
                                + " -- stage a translator jar (see :examples-translator) before building a RoutingProfile");
            }
            translator = found.get(0);
        }
        return translator;
    }
}
