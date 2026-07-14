package io.github.intisy.ai.examples;

import io.github.intisy.ai.examples.support.ProvidersDirectory;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts the four routing behaviors end to end, through the real jar-loaded providers and the real
 * Router: model rewrite, cross-provider fallback, tier exhaustion, and the {@code /v1/models}
 * catalog. Each assertion checks behavior (status + echoed body), never printed text.
 */
class RoutingIntegrationTest {

    @Test
    void routesFallbackRewriteExhaustionAndModels() throws IOException {
        Path providersDir = requireProvidersDir();
        RoutingDemo.Result result = RoutingDemo.execute(providersDir);

        // (a) model rewrite: the request asked for claude-haiku-4 but the echo provider served the
        // backend model the tier maps to (m-echo-haiku); the requested id must not appear.
        assertEquals(200, result.normal.status);
        assertTrue(result.normal.body.contains("\"model\":\"m-echo-haiku\""),
                "served body should carry the rewritten backend model: " + result.normal.body);
        assertFalse(result.normal.body.contains("claude-haiku-4"),
                "the requested tier id must have been rewritten, not echoed: " + result.normal.body);

        // (b) fallback: the primary (ratelimited) 429s, so the healthy echo provider serves m-echo-opus.
        assertEquals(200, result.fallback.status);
        assertTrue(result.fallback.body.contains("(served by m-echo-opus)"),
                "opus tier should fall back to echo/m-echo-opus: " + result.fallback.body);

        // (c) exhaustion: the sonnet tier's only provider is rate-limited -> synthesized native 429.
        assertEquals(429, result.exhaustion.status);
        assertTrue(result.exhaustion.body.contains("rate_limit_error"),
                "exhausted tier should synthesize a native rate_limit_error: " + result.exhaustion.body);

        // (d) /v1/models: the catalog lists every model the discovered providers cached.
        assertEquals(200, result.models.status);
        for (String model : new String[] {"m-echo-opus", "m-echo-haiku", "m-busy-opus", "m-busy-sonnet"}) {
            assertTrue(result.models.body.contains("\"id\":\"" + model + "\""),
                    "/v1/models should list " + model + ": " + result.models.body);
        }
    }

    private static Path requireProvidersDir() {
        Path providersDir = ProvidersDirectory.locate();
        assertNotNull(providersDir, "provider jar dir not staged; run through `gradlew :examples:test`");
        return providersDir;
    }
}
