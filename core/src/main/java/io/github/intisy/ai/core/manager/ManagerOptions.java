package io.github.intisy.ai.core.manager;

import io.github.intisy.ai.core.oauth.OAuthConfig;
import io.github.intisy.ai.core.select.Strategy;
import io.github.intisy.ai.core.store.Account;

import java.util.function.BiPredicate;

/**
 * Java analog of the {@code opts} object passed to the JS {@code AccountManager} constructor
 * (see {@code libs/core-auth/src/manager.ts:18-28}).
 */
public class ManagerOptions {
    public Strategy strategy = Strategy.HYBRID;
    public OAuthConfig oauth;                       // null => refresh is disabled (ensureAccess returns the stored access as-is)

    /** Matches JS ratelimit.ts {@code calculateBackoffMs} defaults: baseMs=1000, maxMs=5*60*1000. */
    public long backoffBaseMs = 1000L;
    public long backoffMaxMs = 5 * 60 * 1000L;

    /** Extra availability predicate {@code (account, lane) -> boolean}, AND-ed onto {@link io.github.intisy.ai.core.select.RateLimitMath#isAvailable}. */
    public BiPredicate<Account, String> extraAvailable;

    public HttpFetcher http = new UrlConnectionFetcher();
}
