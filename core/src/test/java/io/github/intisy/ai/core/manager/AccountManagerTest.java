package io.github.intisy.ai.core.manager;

import io.github.intisy.ai.core.oauth.OAuthConfig;
import io.github.intisy.ai.core.oauth.Refreshed;
import io.github.intisy.ai.core.oauth.TokenRefresh;
import io.github.intisy.ai.core.oauth.TokenRefreshError;
import io.github.intisy.ai.core.select.Strategy;
import io.github.intisy.ai.core.store.Account;
import io.github.intisy.ai.core.store.AccountStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity tests for the Java port of {@code libs/core-auth/src/oauth.ts} +
 * {@code libs/core-auth/src/manager.ts}. All times are passed in explicitly (or read once into
 * a local) and the outbound HTTP call is a fake, so these tests are fully deterministic and
 * never touch the network.
 */
class AccountManagerTest {

    /** Injectable fake seam: records the last request and returns a canned response. */
    private static class FakeHttpFetcher implements HttpFetcher {
        int responseStatus = 200;
        String responseBody = "{}";
        String lastUrl;
        Map<String, String> lastForm;
        int callCount = 0;

        @Override
        public Resp post(String url, Map<String, String> form) {
            callCount++;
            lastUrl = url;
            lastForm = form;
            return new Resp(responseStatus, responseBody);
        }
    }

    private static OAuthConfig oauthConfig() {
        OAuthConfig cfg = new OAuthConfig();
        cfg.tokenUrl = "https://example.com/token";
        cfg.clientId = "client-123";
        return cfg;
    }

    // ---- TokenRefresh.accessTokenExpired ----------------------------------------------------

    @Test
    void accessTokenExpired_trueWhenWithinSixtySecondSkewBuffer() {
        long now = 1_000_000L;

        Account atEdge = new Account();
        atEdge.access = "tok";
        atEdge.expires = now + 60_000L; // exactly at the buffer -> still expired (<=)
        assertTrue(TokenRefresh.accessTokenExpired(atEdge, now));

        Account beyondEdge = new Account();
        beyondEdge.access = "tok";
        beyondEdge.expires = now + 60_001L;
        assertFalse(TokenRefresh.accessTokenExpired(beyondEdge, now));

        Account missingAccess = new Account();
        missingAccess.expires = now + 999_999L;
        assertTrue(TokenRefresh.accessTokenExpired(missingAccess, now));

        Account missingExpires = new Account();
        missingExpires.access = "tok";
        assertTrue(TokenRefresh.accessTokenExpired(missingExpires, now));
    }

    // ---- TokenRefresh.refresh ----------------------------------------------------------------

    @Test
    void refresh_postsGrantTypeRefreshTokenAndReturnsAccessExpires() {
        long now = 1_000_000L;
        FakeHttpFetcher fake = new FakeHttpFetcher();
        fake.responseStatus = 200;
        fake.responseBody = "{\"access_token\":\"new-access\",\"expires_in\":3600,\"refresh_token\":\"new-refresh\"}";

        Refreshed result = TokenRefresh.refresh("old-refresh", oauthConfig(), fake, now);

        assertEquals("new-access", result.access);
        assertEquals(now + 3_600_000L, result.expires);
        assertEquals("new-refresh", result.refresh);

        assertEquals("https://example.com/token", fake.lastUrl);
        assertEquals("refresh_token", fake.lastForm.get("grant_type"));
        assertEquals("old-refresh", fake.lastForm.get("refresh_token"));
        assertEquals("client-123", fake.lastForm.get("client_id"));
    }

    @Test
    void refresh_missingRefreshTokenFallsBackToOldOne() {
        long now = 1_000_000L;
        FakeHttpFetcher fake = new FakeHttpFetcher();
        fake.responseBody = "{\"access_token\":\"new-access\",\"expires_in\":60}";

        Refreshed result = TokenRefresh.refresh("old-refresh", oauthConfig(), fake, now);

        assertEquals("old-refresh", result.refresh); // JS: `refresh_token || refreshToken`
    }

    @Test
    void refresh_invalidGrantThrowsRevokedTokenRefreshError() {
        FakeHttpFetcher fake = new FakeHttpFetcher();
        fake.responseStatus = 400;
        fake.responseBody = "{\"error\":\"invalid_grant\"}";

        TokenRefreshError err = assertThrows(TokenRefreshError.class,
                () -> TokenRefresh.refresh("old-refresh", oauthConfig(), fake, 1_000_000L));

        assertTrue(err.revoked);
    }

    @Test
    void refresh_otherErrorCodeIsNotRevoked() {
        FakeHttpFetcher fake = new FakeHttpFetcher();
        fake.responseStatus = 500;
        fake.responseBody = "{\"error\":\"server_error\"}";

        TokenRefreshError err = assertThrows(TokenRefreshError.class,
                () -> TokenRefresh.refresh("old-refresh", oauthConfig(), fake, 1_000_000L));

        assertFalse(err.revoked);
    }

    // ---- AccountManager.acquire / ensureAccess -----------------------------------------------

    @Test
    void acquire_returnsEnabledAccountAndRefreshesExpiredTokenViaFakeFetcher() throws Exception {
        Path configFolder = Files.createTempDirectory("ai-manager-acquire");
        AccountStore store = new AccountStore(configFolder);

        Account account = new Account();
        account.id = "acc1";
        account.enabled = true;
        account.refresh = "old-refresh";
        account.access = "stale-access";
        account.expires = 0L; // already expired
        store.add("provider", account);

        FakeHttpFetcher fake = new FakeHttpFetcher();
        fake.responseBody = "{\"access_token\":\"fresh-access\",\"expires_in\":3600,\"refresh_token\":\"rotated-refresh\"}";

        ManagerOptions opts = new ManagerOptions();
        opts.http = fake;
        opts.oauth = oauthConfig();

        AccountManager manager = new AccountManager("provider", store, opts);

        Acquired acquired = manager.acquire("messages");

        assertNotNull(acquired);
        assertEquals("acc1", acquired.account.id);
        assertEquals("fresh-access", acquired.access);
        assertEquals(1, fake.callCount); // the fake was actually called

        Account persisted = store.list("provider").get(0);
        assertEquals("fresh-access", persisted.access);
        assertEquals("rotated-refresh", persisted.refresh);
        assertNotNull(persisted.lastUsed); // claimed by acquire
    }

    @Test
    void acquire_returnsNullWhenPoolIsEmpty() throws Exception {
        Path configFolder = Files.createTempDirectory("ai-manager-empty");
        AccountStore store = new AccountStore(configFolder);
        AccountManager manager = new AccountManager("provider", store, new ManagerOptions());

        assertNull(manager.acquire("messages"));
    }

    @Test
    void ensureAccess_disablesAccountWhenRefreshTokenRevoked() throws Exception {
        Path configFolder = Files.createTempDirectory("ai-manager-revoked");
        AccountStore store = new AccountStore(configFolder);

        Account account = new Account();
        account.id = "acc1";
        account.enabled = true;
        account.refresh = "old-refresh";
        account.expires = 0L;
        store.add("provider", account);

        FakeHttpFetcher fake = new FakeHttpFetcher();
        fake.responseStatus = 400;
        fake.responseBody = "{\"error\":\"invalid_grant\"}";

        ManagerOptions opts = new ManagerOptions();
        opts.http = fake;
        opts.oauth = oauthConfig();

        AccountManager manager = new AccountManager("provider", store, opts);

        assertThrows(TokenRefreshError.class, () -> manager.ensureAccess("acc1"));

        Account persisted = store.list("provider").get(0);
        assertEquals(Boolean.FALSE, persisted.enabled);
        assertEquals("refresh token revoked", persisted.disabledReason);
    }

    // ---- AccountManager.reportRateLimit / reportError / reportSuccess -----------------------

    @Test
    void reportRateLimit_thenNextAcquireSkipsTheRateLimitedAccount() throws Exception {
        Path configFolder = Files.createTempDirectory("ai-manager-ratelimit");
        AccountStore store = new AccountStore(configFolder);

        Account a0 = new Account();
        a0.id = "acc0";
        a0.enabled = true;
        store.add("provider", a0);

        Account a1 = new Account();
        a1.id = "acc1";
        a1.enabled = true;
        store.add("provider", a1);

        ManagerOptions opts = new ManagerOptions();
        opts.strategy = Strategy.ROUND_ROBIN;
        AccountManager manager = new AccountManager("provider", store, opts);

        Acquired first = manager.acquire("messages");
        assertNotNull(first);

        manager.reportRateLimit(first.account.id, "messages", System.currentTimeMillis() + 60_000L);

        Acquired second = manager.acquire("messages");
        assertNotNull(second);
        assertNotEquals(first.account.id, second.account.id); // round-robin skips the now rate-limited account
    }

    @Test
    void reportError_setsCoolingDownUntilViaBackoff() throws Exception {
        Path configFolder = Files.createTempDirectory("ai-manager-error");
        AccountStore store = new AccountStore(configFolder);

        Account account = new Account();
        account.id = "acc1";
        account.enabled = true;
        store.add("provider", account);

        AccountManager manager = new AccountManager("provider", store, new ManagerOptions());

        long before = System.currentTimeMillis();
        manager.reportError("acc1", 0, "boom");
        long after = System.currentTimeMillis();

        Account persisted = store.list("provider").get(0);
        assertNotNull(persisted.coolingDownUntil);
        assertTrue(persisted.coolingDownUntil > before); // cooldown pushed into the future
        assertTrue(persisted.coolingDownUntil <= after + 1000); // base backoff (attempt 0) is well under a second
        assertEquals("boom", persisted.cooldownReason);
    }

    @Test
    void reportSuccess_clearsCooldownAndBumpsLastUsed() throws Exception {
        Path configFolder = Files.createTempDirectory("ai-manager-success");
        AccountStore store = new AccountStore(configFolder);

        Account account = new Account();
        account.id = "acc1";
        account.enabled = true;
        account.coolingDownUntil = System.currentTimeMillis() + 60_000L;
        account.cooldownReason = "boom";
        store.add("provider", account);

        AccountManager manager = new AccountManager("provider", store, new ManagerOptions());
        manager.reportSuccess("acc1");

        Account persisted = store.list("provider").get(0);
        assertEquals(0L, persisted.coolingDownUntil);
        assertNull(persisted.cooldownReason);
        assertNotNull(persisted.lastUsed);
    }

    // ---- AccountManager.nextAvailableAt / refresh (force) ------------------------------------

    @Test
    void nextAvailableAt_returnsSoonestResetAcrossPool() throws Exception {
        Path configFolder = Files.createTempDirectory("ai-manager-next");
        AccountStore store = new AccountStore(configFolder);

        long now = System.currentTimeMillis();
        Account a0 = new Account();
        a0.id = "acc0";
        a0.enabled = true;
        a0.coolingDownUntil = now + 10_000L;
        store.add("provider", a0);

        Account a1 = new Account();
        a1.id = "acc1";
        a1.enabled = true;
        a1.coolingDownUntil = now + 2_000L;
        store.add("provider", a1);

        AccountManager manager = new AccountManager("provider", store, new ManagerOptions());
        long next = manager.nextAvailableAt(null);

        assertTrue(next <= now + 10_000L);
        assertTrue(next >= now + 2_000L - 1000); // soonest of the two, floored to "now" internally
    }

    @Test
    void refresh_forcesRefreshRegardlessOfExpiry() throws Exception {
        Path configFolder = Files.createTempDirectory("ai-manager-force-refresh");
        AccountStore store = new AccountStore(configFolder);

        Account account = new Account();
        account.id = "acc1";
        account.enabled = true;
        account.refresh = "old-refresh";
        account.access = "still-valid-access";
        account.expires = System.currentTimeMillis() + 999_999_999L; // nowhere near expiry
        store.add("provider", account);

        FakeHttpFetcher fake = new FakeHttpFetcher();
        fake.responseBody = "{\"access_token\":\"forced-access\",\"expires_in\":3600}";

        ManagerOptions opts = new ManagerOptions();
        opts.http = fake;
        opts.oauth = oauthConfig();

        AccountManager manager = new AccountManager("provider", store, opts);
        String access = manager.refresh("acc1");

        assertEquals("forced-access", access);
        assertEquals(1, fake.callCount); // called even though the token wasn't expired
        assertEquals("forced-access", store.list("provider").get(0).access);
    }
}
