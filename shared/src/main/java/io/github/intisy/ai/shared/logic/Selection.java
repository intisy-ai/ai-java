package io.github.intisy.ai.shared.logic;

import io.github.intisy.ai.shared.model.Account;
import io.github.intisy.ai.shared.model.AccountPool;

import java.util.LinkedHashMap;
import java.util.function.BiPredicate;

/**
 * Picks an account index given availability. Strategies: STICKY (keep the cursor until it
 * becomes unavailable), ROUND_ROBIN (advance the cursor on every call), HYBRID (sticky, but
 * fall back to whoever frees up soonest when nobody is currently available). The cursor is
 * per-lane when a lane is given. Ported from {@code libs/core-auth/src/selection.ts}.
 */
public final class Selection {
    private Selection() {
    }

    /**
     * @param available availability predicate {@code (account, lane) -> boolean}; pass
     *                   {@code null} to use {@link RateLimitMath#isAvailable} with {@code now}.
     * @return the selected index, or -1 when none are available (round-robin/sticky) or the
     *         pool is empty.
     */
    public static int selectIndex(AccountPool pool, String lane, long now, Strategy strat,
                                   BiPredicate<Account, String> available) {
        int n = pool.accounts.size();
        if (n == 0) return -1;
        BiPredicate<Account, String> isFree = available != null
                ? available
                : (a, l) -> RateLimitMath.isAvailable(a, l, now);
        int cursor = laneCursor(pool, lane);
        // Clamp corrupt cursor to valid range
        if (cursor < 0 || cursor >= n) cursor = 0;
        Strategy s = strat != null ? strat : Strategy.HYBRID;

        if (s == Strategy.ROUND_ROBIN) {
            int i = firstAvailableFrom(pool, (cursor + 1) % n, lane, isFree);
            if (i >= 0) setLaneCursor(pool, lane, i);
            return i;
        }

        // sticky/hybrid: keep the cursor if it's still available
        if (cursor >= 0 && cursor < n && isFree.test(pool.accounts.get(cursor), lane)) {
            return cursor;
        }

        int i = firstAvailableFrom(pool, cursor, lane, isFree);
        if (i >= 0) {
            setLaneCursor(pool, lane, i);
            return i;
        }

        if (s == Strategy.HYBRID) {
            // last resort: claim whoever frees up soonest, even though nobody is available now
            int best = soonestFree(pool, lane, now);
            if (best >= 0) setLaneCursor(pool, lane, best);
            return best;
        }
        return -1;
    }

    private static int laneCursor(AccountPool pool, String lane) {
        if (lane != null && !lane.isEmpty() && pool.activeIndexByLane != null) {
            Integer v = pool.activeIndexByLane.get(lane);
            if (v != null) return v;
        }
        return pool.activeIndex;
    }

    private static void setLaneCursor(AccountPool pool, String lane, int index) {
        if (lane != null && !lane.isEmpty()) {
            if (pool.activeIndexByLane == null) pool.activeIndexByLane = new LinkedHashMap<>();
            pool.activeIndexByLane.put(lane, index);
        } else {
            pool.activeIndex = index;
        }
    }

    private static int firstAvailableFrom(AccountPool pool, int start, String lane,
                                           BiPredicate<Account, String> available) {
        int n = pool.accounts.size();
        for (int step = 0; step < n; step++) {
            int i = (start + step) % n;
            if (available.test(pool.accounts.get(i), lane)) return i;
        }
        return -1;
    }

    /** Soonest-free account across the whole pool, the hybrid fallback so the caller can wait. */
    private static int soonestFree(AccountPool pool, String lane, long now) {
        int best = -1;
        long bestAt = Long.MAX_VALUE;
        for (int i = 0; i < pool.accounts.size(); i++) {
            long at = RateLimitMath.availableAt(pool.accounts.get(i), lane, now);
            if (at < bestAt) {
                bestAt = at;
                best = i;
            }
        }
        return best;
    }
}
