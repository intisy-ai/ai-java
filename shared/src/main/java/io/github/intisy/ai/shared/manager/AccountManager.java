package io.github.intisy.ai.shared.manager;

import io.github.intisy.ai.shared.logic.RateLimitMath;
import io.github.intisy.ai.shared.logic.Selection;
import io.github.intisy.ai.shared.model.Account;
import io.github.intisy.ai.shared.oauth.Refreshed;
import io.github.intisy.ai.shared.oauth.TokenRefresh;
import io.github.intisy.ai.shared.oauth.TokenRefreshError;
import io.github.intisy.ai.shared.spi.Clock;
import io.github.intisy.ai.shared.spi.HttpClient;
import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.Random;
import io.github.intisy.ai.shared.store.AccountStore;

import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The generic multi-account engine (storage, selection, rate-limit/cooldown, OAuth refresh).
 * Java port of the JS {@code AccountManager} (see {@code libs/core-auth/src/manager.ts}),
 * rewired onto the shared SPIs: {@link AccountStore} (itself on the {@code Store}/{@code JsonCodec}
 * SPIs), {@link Clock} for {@code now}, {@link Random} for backoff jitter, and {@link HttpClient}+
 * {@link JsonCodec} for the OAuth refresh call. No locks/threads here: the atomic
 * read-modify-write is {@code Store.update}'s concern (the implementation's), and the network
 * refresh call in {@link #acquire} is sequenced OUTSIDE any store call, matching the JS
 * "claim under the lock, refresh outside it" behavior without any actual locking in this class.
 *
 * <p>Note the JS proxy-aware {@code oauthWithProxy} wrapper (routing the refresh call through
 * the account's sticky proxy) is NOT ported here — proxy support doesn't exist in this module
 * yet; {@link ManagerOptions#oauth} is passed to {@link TokenRefresh#refresh} unmodified.
 */
public class AccountManager {
    private final String providerId;
    private final AccountStore store;
    private final HttpClient http;
    private final Clock clock;
    private final Random random;
    private final JsonCodec json;
    private final ManagerOptions opts;

    public AccountManager(String providerId, AccountStore store, HttpClient http, Clock clock, Random random,
                           JsonCodec json, ManagerOptions opts) {
        this.providerId = providerId;
        this.store = store;
        this.http = http;
        this.clock = clock;
        this.random = random;
        this.json = json;
        this.opts = opts != null ? opts : new ManagerOptions();
    }

    // matches JS `this.available = (account, lane, now) => builtinAvailable(...) && (!this.extraAvailable || this.extraAvailable(...))`
    private boolean isAvailable(Account account, String lane, long now) {
        if (!RateLimitMath.isAvailable(account, lane, now)) return false;
        return opts.extraAvailable == null || opts.extraAvailable.test(account, lane);
    }

    /**
     * Selection + the {@code lastUsed} claim ONLY -- NO network token refresh. This is the
     * persisted half of {@link #acquire}, split out (Phase 3 Task 1 of the npm migration) so a
     * caller that wants to interleave the refresh call with its own proxy/fetch plumbing --
     * rather than {@link #ensureAccess}'s built-in {@code HttpClient} -- can claim here and run
     * {@link io.github.intisy.ai.shared.oauth.TokenRefresh#refresh} itself afterward. Runs inside
     * {@code store.update} (atomic per the {@code Store} SPI's contract). Returns the claimed
     * account's CURRENT stored {@code access} token as-is (no expiry check, no refresh) --
     * {@code null} when nobody in the pool is available.
     */
    public Acquired selectAndClaim(String lane) {
        long now = clock.now();
        String[] claimedId = new String[1];
        store.update(providerId, pool -> {
            int index = Selection.selectIndex(pool, lane, now, opts.strategy, (a, l) -> isAvailable(a, l, now));
            if (index < 0) return;
            Account account = pool.accounts.get(index);
            account.lastUsed = now;
            claimedId[0] = account.id;
        });
        if (claimedId[0] == null) return null;
        Account account = findAccount(claimedId[0]);
        return new Acquired(account, account != null ? account.access : null);
    }

    /**
     * {@link #selectAndClaim}, then a network token refresh ({@link #ensureAccess}) OUTSIDE the
     * store-update call so a slow refresh never blocks other writers (JS manager.ts: {@code
     * acquire}).
     */
    public Acquired acquire(String lane) {
        Acquired claimed = selectAndClaim(lane);
        if (claimed == null) return null;

        String id = claimed.account.id;
        String access = ensureAccess(id);
        Account account = findAccount(id);
        return new Acquired(account, access);
    }

    /**
     * Refreshes the access token if expired (and a refresh token + oauth config are present),
     * persisting the new access/expires/refresh. A revoked refresh token disables the account
     * so selection skips it going forward (JS manager.ts: {@code ensureAccess}).
     */
    public String ensureAccess(String id) {
        Account account = findAccount(id);
        if (account == null) return null;
        long now = clock.now();
        if (!TokenRefresh.accessTokenExpired(account, now)) return account.access;
        if (opts.oauth == null || account.refresh == null) return account.access;
        try {
            Refreshed refreshed = TokenRefresh.refresh(account.refresh, opts.oauth, http, json, now);
            persistRefresh(id, refreshed);
            return refreshed.access;
        } catch (TokenRefreshError e) {
            if (e.revoked) {
                mutate(id, a -> {
                    a.enabled = false;
                    a.disabledReason = "refresh token revoked";
                });
            }
            throw e;
        }
    }

    public void reportRateLimit(String id, String lane, long resetMs) {
        mutate(id, account -> {
            if (account.rateLimitResetTimes == null) account.rateLimitResetTimes = new LinkedHashMap<>();
            account.rateLimitResetTimes.put(lane, resetMs);
        });
    }

    public void reportError(String id, int attempt, String reason) {
        long ms = RateLimitMath.calculateBackoffMs(attempt, opts.backoffBaseMs, opts.backoffMaxMs, true, random);
        long resumeAt = clock.now() + ms;
        mutate(id, account -> {
            account.coolingDownUntil = resumeAt;
            account.cooldownReason = reason != null ? reason : "transient error";
        });
    }

    public void reportSuccess(String id) {
        long now = clock.now();
        mutate(id, account -> {
            account.coolingDownUntil = 0L;
            account.cooldownReason = null;
            account.lastUsed = now;
        });
    }

    public void mutate(String id, Consumer<Account> fn) {
        store.update(providerId, pool -> {
            for (Account a : pool.accounts) {
                if (Objects.equals(a.id, id)) {
                    fn.accept(a);
                    return;
                }
            }
        });
    }

    /**
     * Soonest epoch-ms any account in the pool becomes available for {@code lane}, or
     * {@code null} if none ever will (matches JS {@code manager.ts}: {@code best === Infinity
     * ? null : best}).
     */
    public Long nextAvailableAt(String lane) {
        long now = clock.now();
        long best = Long.MAX_VALUE;
        for (Account account : store.list(providerId)) {
            best = Math.min(best, RateLimitMath.availableAt(account, lane, now));
        }
        return best == Long.MAX_VALUE ? null : best;
    }

    /** Forces a token refresh regardless of expiry (manual "refresh token" action). Returns the new access token, or {@code null} if there's nothing to refresh. */
    public String refresh(String id) {
        Account account = findAccount(id);
        if (account == null || opts.oauth == null || account.refresh == null) return null;
        long now = clock.now();
        Refreshed refreshed = TokenRefresh.refresh(account.refresh, opts.oauth, http, json, now);
        persistRefresh(id, refreshed);
        return refreshed.access;
    }

    private void persistRefresh(String id, Refreshed refreshed) {
        mutate(id, a -> {
            a.access = refreshed.access;
            a.expires = refreshed.expires;
            if (refreshed.refresh != null) a.refresh = refreshed.refresh;
        });
    }

    private Account findAccount(String id) {
        for (Account a : store.list(providerId)) {
            if (Objects.equals(a.id, id)) return a;
        }
        return null;
    }
}
