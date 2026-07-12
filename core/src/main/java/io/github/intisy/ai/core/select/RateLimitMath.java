package io.github.intisy.ai.core.select;

import io.github.intisy.ai.core.store.Account;

import java.util.function.DoubleSupplier;

/**
 * Generic availability + backoff math; "lanes" are arbitrary strings a driver uses to
 * partition rate limits. Ported from {@code libs/core-auth/src/ratelimit.ts}.
 */
public final class RateLimitMath {
    private RateLimitMath() {
    }

    public static boolean isEnabled(Account account) {
        return account.enabled == null || account.enabled;
    }

    public static boolean isCoolingDown(Account account, long now) {
        return account.coolingDownUntil != null && account.coolingDownUntil > now;
    }

    public static boolean isLaneRateLimited(Account account, String lane, long now) {
        if (lane == null || lane.isEmpty() || account.rateLimitResetTimes == null) return false;
        Long until = account.rateLimitResetTimes.get(lane);
        return until != null && until > now;
    }

    public static boolean isAvailable(Account account, String lane, long now) {
        if (!isEnabled(account)) return false;
        if (isCoolingDown(account, now)) return false;
        if (isLaneRateLimited(account, lane, now)) return false;
        return true;
    }

    /**
     * Soonest epoch ms this account is usable again for {@code lane}; {@code Long.MAX_VALUE}
     * (the "Infinity" sentinel) if the account is disabled.
     */
    public static long availableAt(Account account, String lane) {
        if (!isEnabled(account)) return Long.MAX_VALUE;
        long t = 0L;
        if (account.coolingDownUntil != null) t = Math.max(t, account.coolingDownUntil);
        if (lane != null && !lane.isEmpty() && account.rateLimitResetTimes != null) {
            Long until = account.rateLimitResetTimes.get(lane);
            if (until != null) t = Math.max(t, until);
        }
        return t;
    }

    /**
     * {@code min(maxMs, baseMs * 2^attempt)}, halved + jittered unless {@code jitter} is
     * {@code false} (in which case the raw value is returned with NO randomness).
     */
    public static long calculateBackoffMs(int attempt, long baseMs, long maxMs, boolean jitter) {
        return calculateBackoffMs(attempt, baseMs, maxMs, jitter, Math::random);
    }

    /**
     * Package-private seam so tests can inject a deterministic RNG. {@code rng} is only
     * consulted when {@code jitter} is {@code true}.
     */
    static long calculateBackoffMs(int attempt, long baseMs, long maxMs, boolean jitter, DoubleSupplier rng) {
        long raw = Math.min(maxMs, (long) (baseMs * Math.pow(2, Math.max(0, attempt))));
        if (!jitter) return raw;
        return (long) Math.floor(raw / 2.0 + rng.getAsDouble() * (raw / 2.0));
    }
}
