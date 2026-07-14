package io.github.intisy.ai.examples;

import io.github.intisy.ai.examples.support.CollectingNotifier;
import io.github.intisy.ai.shared.manager.ManagerOptions;
import io.github.intisy.ai.shared.select.RateLimitMath;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts the injected SPIs actually take effect: the custom Env value is returned, the custom
 * Notifier and Logger capture the router's fallback decision, and the fixed clock + seeded random
 * make the account manager's backoff a single, predictable timestamp.
 */
class CustomSpiIntegrationTest {

    @Test
    void injectedSpisAreUsedAndBackoffIsDeterministic() {
        CustomSpiDemo.Result result = CustomSpiDemo.execute();

        assertEquals("eu-central", result.envValue, "the injected Env should back env lookups");

        assertEquals(1, result.notices.size(), "the router fallback should have produced one notice");
        CollectingNotifier.Notice notice = result.notices.get(0);
        assertTrue(notice.message.contains("rate-limited"), "notice should describe the fallback: " + notice.message);

        assertTrue(result.logLines.stream().anyMatch(line -> line.startsWith("[router] ")),
                "the injected logger should have captured prefixed router logs: " + result.logLines);
        assertTrue(result.logLines.stream().anyMatch(line -> line.contains("trying next fallback")),
                "the router should have logged its fallback decision: " + result.logLines);

        ManagerOptions defaults = new ManagerOptions();
        long expectedBackoff = RateLimitMath.calculateBackoffMs(0, defaults.backoffBaseMs, defaults.backoffMaxMs,
                true, CustomSpiDemo.RANDOM_VALUE);
        assertEquals(CustomSpiDemo.CLOCK_START_MS + expectedBackoff, result.backoffResumeAt,
                "reportError's cooldown should be fixed clock + deterministic jittered backoff");

        assertTrue(result.jsonParseCount > 0,
                "the injected JsonCodec should have been used by the engine (parse called at least once)");
    }
}
