package io.github.intisy.ai.examples;

import io.github.intisy.ai.shared.manager.ManagerOptions;
import io.github.intisy.ai.shared.select.RateLimitMath;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts the account engine's behavior end to end (via {@link AccountManagerDemo#execute}) against
 * the real local token endpoint, with a fixed clock + seeded random making every timestamp exact:
 * a rate-limited account blocks selection until its reset, transient errors apply a deterministic
 * backoff, a successful refresh updates the stored token, and a revoked refresh disables the account.
 */
class AccountManagerIntegrationTest {

    @Test
    void cooldownBackoffRefreshAndRevoke() throws IOException {
        AccountManagerDemo.Result result = AccountManagerDemo.execute();

        long now = AccountManagerDemo.CLOCK_START_MS + AccountManagerDemo.RATE_LIMIT_MS;

        // acquire picked the seeded account.
        assertEquals("user@example.com", result.acquiredEmail);

        // reportRateLimit -> the account is unavailable until exactly its reset time (deterministic clock).
        assertTrue(result.acquireBlockedDuringCooldown, "acquire must return null while the only account is rate-limited");
        assertEquals(AccountManagerDemo.CLOCK_START_MS + AccountManagerDemo.RATE_LIMIT_MS,
                result.nextAvailableAfterRateLimit, "nextAvailableAt should equal the rate-limit reset time");

        // After the clock advances past the reset, the account is selectable again.
        assertTrue(result.acquireSucceededAfterReset);

        // reportError -> a deterministic backoff (base/max defaults, seeded random 0.5, attempt 0).
        ManagerOptions defaults = new ManagerOptions();
        long expectedBackoff = RateLimitMath.calculateBackoffMs(0, defaults.backoffBaseMs, defaults.backoffMaxMs, true, 0.5);
        assertEquals(now + expectedBackoff, result.nextAvailableAfterBackoff,
                "reportError should set a cooldown of exactly the jittered backoff from the fixed clock");

        // reportSuccess -> transient cooldown cleared (only the already-elapsed rate-limit floor remains).
        assertEquals(now, result.nextAvailableAfterSuccess, "reportSuccess should clear the transient cooldown");

        // Refresh round trip: exactly one real POST, and the stored token is the endpoint's new one.
        assertEquals(1, result.refreshRequestCount, "the expired token should have triggered exactly one refresh POST");
        assertEquals("refreshed-access-token", result.refreshedAccessToken);
        assertEquals("rotated-refresh-token", result.refreshedRefreshToken);
        assertNotNull(result.accountsJson);
        assertTrue(result.accountsJson.contains("refreshed-access-token"),
                "the persisted accounts.json should carry the refreshed access token: " + result.accountsJson);

        // Revoked refresh (invalid_grant) disables the account.
        assertEquals(Boolean.FALSE, result.revokedAccountEnabled, "an invalid_grant refresh must disable the account");
        assertEquals("refresh token revoked", result.revokedDisabledReason);

        // The injected HttpClient (not the default) is what performed both refresh POSTs.
        assertEquals(2, result.httpSendCount,
                "the injected HttpClient should have sent both refresh requests (expiring + revoked)");

        // managerOptions(STICKY) proven against a real 2-account pool: held, switched, then (the
        // behavior that actually distinguishes STICKY from the default HYBRID) refused to return an
        // account once the whole pool was rate-limited.
        assertEquals("sticky-a@example.com", result.stickyFirstAcquire);
        assertEquals(result.stickyFirstAcquire, result.stickySecondAcquire,
                "STICKY should hold the same account across acquires while it is available");
        assertEquals("sticky-b@example.com", result.stickyAfterPrimaryRateLimited,
                "STICKY should switch to the other account once the held one is rate-limited");
        assertTrue(result.stickyBlockedWhenAllRateLimited,
                "STICKY must return null when the whole pool is unavailable (HYBRID would not)");
    }
}
