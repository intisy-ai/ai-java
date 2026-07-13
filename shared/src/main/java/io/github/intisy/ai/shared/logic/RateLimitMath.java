package io.github.intisy.ai.shared.logic;

import io.github.intisy.ai.shared.model.Account;
import io.github.intisy.ai.shared.spi.Random;

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
     * (the "Infinity" sentinel) if the account is disabled. Floors to {@code now} (matches JS
     * {@code Math.max(t, now)}) so an account whose cooldown/rate-limit timestamps are already
     * in the past (but is still unavailable via a custom predicate) reports "now", not a stale
     * past instant.
     */
    public static long availableAt(Account account, String lane, long now) {
        if (!isEnabled(account)) return Long.MAX_VALUE;
        long t = 0L;
        if (account.coolingDownUntil != null) t = Math.max(t, account.coolingDownUntil);
        if (lane != null && !lane.isEmpty() && account.rateLimitResetTimes != null) {
            Long until = account.rateLimitResetTimes.get(lane);
            if (until != null) t = Math.max(t, until);
        }
        return Math.max(t, now);
    }

    /**
     * {@code min(maxMs, baseMs * 2^attempt)}, halved + jittered unless {@code jitter} is
     * {@code false} (in which case the result is the raw value with NO randomness --
     * {@code jitterFactor} is not consulted at all).
     *
     * @param jitterFactor a value in {@code [0, 1)}, e.g. from {@link Random#next()}; ignored
     *                      when {@code jitter} is {@code false}.
     */
    public static long calculateBackoffMs(int attempt, long baseMs, long maxMs, boolean jitter, double jitterFactor) {
        long raw = Math.min(maxMs, (long) (baseMs * Math.pow(2, Math.max(0, attempt))));
        if (!jitter) return raw;
        return (long) Math.floor(raw / 2.0 + jitterFactor * (raw / 2.0));
    }

    /**
     * SPI-based seam: consults {@code rng.next()} only when {@code jitter} is {@code true},
     * so callers stay fully deterministic in tests by injecting a fixed {@link Random}.
     */
    public static long calculateBackoffMs(int attempt, long baseMs, long maxMs, boolean jitter, Random rng) {
        double jitterFactor = jitter ? rng.next() : 0.0;
        return calculateBackoffMs(attempt, baseMs, maxMs, jitter, jitterFactor);
    }

    /**
     * Convenience overload for the {@code jitter == false} path, where no randomness is
     * needed at all -- exact {@code min(maxMs, baseMs * 2^attempt)}.
     */
    public static long calculateBackoffMs(int attempt, long baseMs, long maxMs, boolean jitter) {
        return calculateBackoffMs(attempt, baseMs, maxMs, jitter, 0.0);
    }
}
