package io.github.intisy.ai.examples;

import io.github.intisy.ai.examples.support.ProvidersDirectory;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the real {@code :examples-provider} jar (staged into the providers directory by Gradle) is
 * discovered purely via {@code ServiceLoader} — both providers from one jar — and routes a real
 * request. Also proves the {@code close()} lifecycle releases the jar classloader: a fresh AiJava
 * over the same directory re-discovers cleanly after the first was closed.
 */
class ProviderRegistryIntegrationTest {

    @Test
    void jarDiscoversBothProviders_routesThroughThem_andReDiscoversAfterClose() throws IOException {
        Path providersDir = requireProvidersDir();

        List<String> ids = ProviderRegistryDemo.discover(providersDir);
        assertTrue(ids.contains("echo"), "echo provider should be discovered from the jar: " + ids);
        assertTrue(ids.contains("ratelimited"), "ratelimited provider should be discovered from the jar: " + ids);
        assertEquals(2, ids.size(), "exactly the two example providers should be discovered: " + ids);

        // Route a real request through the jar-loaded provider (RoutingDemo builds+closes its AiJava).
        RoutingDemo.Result routed = RoutingDemo.execute(providersDir);
        assertEquals(200, routed.normal.status);
        assertTrue(routed.normal.body.contains("\"id\":\"msg_echo_0001\""),
                "response should be the echo provider's canned Anthropic-shaped body: " + routed.normal.body);

        // The first AiJava was closed inside execute()/discover(); a fresh instance must still work,
        // showing close() released the loader rather than wedging the directory.
        assertEquals(ids, ProviderRegistryDemo.discover(providersDir),
                "a fresh AiJava should re-discover the same providers after the previous one was closed");
    }

    private static Path requireProvidersDir() {
        Path providersDir = ProvidersDirectory.locate();
        assertNotNull(providersDir, "provider jar dir not staged; run through `gradlew :examples:test`");
        return providersDir;
    }
}
