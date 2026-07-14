package io.github.intisy.ai.examples;

import io.github.intisy.ai.examples.support.AdjustableClock;
import io.github.intisy.ai.examples.support.FakeTokenServer;
import io.github.intisy.ai.examples.support.FixedRandom;
import io.github.intisy.ai.examples.support.RecordingHttpClient;
import io.github.intisy.ai.examples.support.Section;
import io.github.intisy.ai.examples.support.Workspace;
import io.github.intisy.ai.jvm.AiJava;
import io.github.intisy.ai.jvm.Storage;
import io.github.intisy.ai.jvm.UrlConnectionHttpClient;
import io.github.intisy.ai.shared.manager.AccountManager;
import io.github.intisy.ai.shared.manager.Acquired;
import io.github.intisy.ai.shared.manager.ManagerOptions;
import io.github.intisy.ai.shared.model.Account;
import io.github.intisy.ai.shared.oauth.OAuthConfig;
import io.github.intisy.ai.shared.oauth.TokenRefreshError;
import io.github.intisy.ai.shared.select.Strategy;
import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.Store;
import io.github.intisy.ai.shared.store.AccountStore;

import java.io.IOException;

/**
 * Shows the account engine end to end against a REAL local OAuth token endpoint (the one true
 * external edge, faked by {@link FakeTokenServer}). Everything else — the store, the HTTP client,
 * the manager — is real. A fixed clock and seeded random make cooldown/backoff a single predictable
 * timestamp. It walks: acquire &rarr; rate-limit &rarr; cooldown respected &rarr; reset &rarr;
 * transient-error backoff &rarr; success clears it &rarr; a stored-refresh-token refresh round trip
 * &rarr; a revoked ({@code invalid_grant}) refresh disabling the account.
 */
public final class AccountManagerDemo {

    private static final String PROVIDER = "example-provider";
    private static final String LANE = "chat";
    private static final String STICKY_PROVIDER = "sticky-provider";
    private static final String STICKY_LANE = "sticky";
    public static final long CLOCK_START_MS = 1_700_000_000_000L;
    public static final long RATE_LIMIT_MS = 60_000L;
    private static final long ONE_DAY_MS = 86_400_000L;

    private AccountManagerDemo() {
    }

    /** Every observable outcome of the walk, captured before the temp store is deleted. */
    public static final class Result {
        public String acquiredEmail;
        public boolean acquireBlockedDuringCooldown;
        public long nextAvailableAfterRateLimit;
        public boolean acquireSucceededAfterReset;
        public long nextAvailableAfterBackoff;
        public long nextAvailableAfterSuccess;
        public String refreshedAccessToken;
        public String refreshedRefreshToken;
        public int refreshRequestCount;
        public Boolean revokedAccountEnabled;
        public String revokedDisabledReason;
        public String accountsJson;
        public int httpSendCount;
        // STICKY selection, proven against a real 2-account pool.
        public String stickyFirstAcquire;
        public String stickySecondAcquire;
        public String stickyAfterPrimaryRateLimited;
        public boolean stickyBlockedWhenAllRateLimited;
    }

    public static void run() throws IOException {
        Result result = execute();

        Section.header("AccountManagerDemo — acquire / cooldown / backoff / refresh / revoke");
        Section.detail("acquired account: " + result.acquiredEmail);
        Section.detail("after reportRateLimit: acquire blocked during cooldown = " + result.acquireBlockedDuringCooldown
                + ", nextAvailableAt = " + result.nextAvailableAfterRateLimit + " (clock start + " + RATE_LIMIT_MS + "ms)");
        Section.detail("after clock advanced past reset: acquire succeeded again = " + result.acquireSucceededAfterReset);
        Section.detail("after reportError (deterministic backoff): nextAvailableAt = " + result.nextAvailableAfterBackoff);
        Section.detail("after reportSuccess (cooldown cleared): nextAvailableAt = " + result.nextAvailableAfterSuccess);
        Section.detail("refresh round trip: token endpoint hit " + result.refreshRequestCount + " time(s), "
                + "stored access token is now '" + result.refreshedAccessToken + "', rotated refresh '"
                + result.refreshedRefreshToken + "'");
        Section.detail("revoked (invalid_grant) refresh disabled the account: enabled=" + result.revokedAccountEnabled
                + ", disabledReason='" + result.revokedDisabledReason + "'");
        Section.detail("injected HttpClient performed " + result.httpSendCount + " refresh POST(s)");
        Section.detail("STICKY selection (2-account pool): acquired " + result.stickyFirstAcquire
                + ", held it again = " + result.stickySecondAcquire
                + ", switched to " + result.stickyAfterPrimaryRateLimited + " once rate-limited, "
                + "then returned null when both were rate-limited = " + result.stickyBlockedWhenAllRateLimited);
    }

    public static Result execute() throws IOException {
        Result result = new Result();
        try (FakeTokenServer tokenServer = FakeTokenServer.start(
                "refreshed-access-token", "rotated-refresh-token", 3600L, "revoked-refresh-token");
             Workspace workspace = Workspace.create("examples-accounts-")) {

            AdjustableClock clock = new AdjustableClock(CLOCK_START_MS);
            ManagerOptions managerOptions = new ManagerOptions();
            // STICKY: hold the chosen account across acquires while it's available, switch when it
            // isn't, and (unlike HYBRID) refuse to hand back an unavailable account when the whole
            // pool is exhausted. The sticky block below proves each of these against a 2-account pool.
            managerOptions.strategy = Strategy.STICKY;
            RecordingHttpClient httpClient = new RecordingHttpClient(new UrlConnectionHttpClient());

            AiJava app = AiJava.builder()
                    .storage(Storage.file(workspace.resolve("config")))
                    .httpClient(httpClient)
                    .clock(clock)
                    .random(new FixedRandom(0.5))
                    .managerOptions(managerOptions)
                    .build();

            Store store = app.store();
            JsonCodec json = app.jsonCodec();
            AccountStore accountStore = new AccountStore(store, json);

            OAuthConfig oauth = new OAuthConfig();
            oauth.tokenUrl = tokenServer.tokenUrl();
            oauth.clientId = "examples-client";
            AccountManager manager = app.accountManager(PROVIDER, oauth);

            // -- selection + cooldown + backoff (valid tokens, so no network refresh happens here) --
            accountStore.add(PROVIDER, account("user@example.com", "user-refresh", "user-access",
                    CLOCK_START_MS + ONE_DAY_MS));

            Acquired acquired = manager.acquire(LANE);
            result.acquiredEmail = acquired != null && acquired.account != null ? acquired.account.email : null;

            manager.reportRateLimit("user@example.com", LANE, clock.now() + RATE_LIMIT_MS);
            result.acquireBlockedDuringCooldown = manager.acquire(LANE) == null;
            result.nextAvailableAfterRateLimit = manager.nextAvailableAt(LANE);

            clock.advanceBy(RATE_LIMIT_MS); // step past the rate-limit reset
            result.acquireSucceededAfterReset = manager.acquire(LANE) != null;

            manager.reportError("user@example.com", 0, "simulated transient error");
            result.nextAvailableAfterBackoff = manager.nextAvailableAt(LANE);

            manager.reportSuccess("user@example.com");
            result.nextAvailableAfterSuccess = manager.nextAvailableAt(LANE);

            // -- STICKY selection over a real 2-account pool (proves the managerOptions strategy) --
            AccountManager sticky = app.accountManager(STICKY_PROVIDER, oauth);
            accountStore.add(STICKY_PROVIDER, account("sticky-a@example.com", "sticky-a-refresh",
                    "sticky-a-access", CLOCK_START_MS + ONE_DAY_MS));
            accountStore.add(STICKY_PROVIDER, account("sticky-b@example.com", "sticky-b-refresh",
                    "sticky-b-access", CLOCK_START_MS + ONE_DAY_MS));

            result.stickyFirstAcquire = emailOf(sticky.acquire(STICKY_LANE));
            result.stickySecondAcquire = emailOf(sticky.acquire(STICKY_LANE)); // held: same account again
            sticky.reportRateLimit("sticky-a@example.com", STICKY_LANE, clock.now() + RATE_LIMIT_MS);
            result.stickyAfterPrimaryRateLimited = emailOf(sticky.acquire(STICKY_LANE)); // switched to b
            sticky.reportRateLimit("sticky-b@example.com", STICKY_LANE, clock.now() + RATE_LIMIT_MS);
            // Whole pool now unavailable: STICKY returns nothing (HYBRID would hand back the soonest-free).
            result.stickyBlockedWhenAllRateLimited = sticky.acquire(STICKY_LANE) == null;

            // -- refresh round trip: an expired access token forces a real POST to the token endpoint --
            accountStore.add(PROVIDER, account("expiring@example.com", "expiring-refresh", "old-access",
                    CLOCK_START_MS - 1000L));
            manager.ensureAccess("expiring@example.com");
            Account refreshed = find(accountStore, "expiring@example.com");
            result.refreshedAccessToken = refreshed.access;
            result.refreshedRefreshToken = refreshed.refresh;
            result.refreshRequestCount = tokenServer.refreshRequestCount();

            // -- revoked refresh token: invalid_grant disables the account for future selection --
            accountStore.add(PROVIDER, account("revoked@example.com", "revoked-refresh-token", "stale-access",
                    CLOCK_START_MS - 1000L));
            try {
                manager.ensureAccess("revoked@example.com");
            } catch (TokenRefreshError expected) {
                // expected: the endpoint reported invalid_grant, so the manager disabled the account
            }
            Account revoked = find(accountStore, "revoked@example.com");
            result.revokedAccountEnabled = revoked.enabled;
            result.revokedDisabledReason = revoked.disabledReason;

            result.accountsJson = store.get("accounts.json");
            result.httpSendCount = httpClient.sendCount();
        }
        return result;
    }

    private static Account account(String email, String refresh, String access, long expires) {
        Account account = new Account();
        account.id = email;
        account.email = email;
        account.refresh = refresh;
        account.access = access;
        account.expires = expires;
        account.enabled = Boolean.TRUE;
        account.addedAt = CLOCK_START_MS;
        return account;
    }

    private static String emailOf(Acquired acquired) {
        return acquired != null && acquired.account != null ? acquired.account.email : null;
    }

    private static Account find(AccountStore accountStore, String id) {
        for (Account account : accountStore.list(PROVIDER)) {
            if (id.equals(account.id)) return account;
        }
        throw new IllegalStateException("account not found: " + id);
    }
}
