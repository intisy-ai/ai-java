package io.github.intisy.ai.core.select;

import io.github.intisy.ai.core.store.Account;
import io.github.intisy.ai.core.store.AccountPool;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Parity tests for the Java port of {@code libs/core-auth/src/selection.ts} and
 * {@code libs/core-auth/src/ratelimit.ts}. All times are passed in explicitly (no
 * wall-clock reads) so the tests are fully deterministic.
 */
class SelectionTest {

    private static Account account(String id) {
        Account a = new Account();
        a.id = id;
        a.enabled = true;
        return a;
    }

    private static AccountPool poolOf(Account... accounts) {
        List<Account> list = new ArrayList<>();
        for (Account a : accounts) list.add(a);
        return new AccountPool(list, 0, new LinkedHashMap<>());
    }

    @Test
    void roundRobinAdvancesCursorAndSkipsLaneRateLimitedAccount() {
        long now = 1_000_000L;
        Account a0 = account("a0");
        Account a1 = account("a1");
        Account a2 = account("a2");
        a1.rateLimitResetTimes = new LinkedHashMap<>();
        a1.rateLimitResetTimes.put("lane1", now + 1000); // a1 is rate-limited on lane1

        AccountPool pool = poolOf(a0, a1, a2);
        pool.activeIndexByLane.put("lane1", 0); // cursor starts at a0

        int result = Selection.selectIndex(pool, "lane1", now, Strategy.ROUND_ROBIN, null);

        assertEquals(2, result); // (0+1)%3=1 is rate-limited, so skip to 2
        assertEquals(2, pool.activeIndexByLane.get("lane1"));
    }

    @Test
    void stickyKeepsCursorWhileAvailableThenMovesWhenNot() {
        long now = 1_000_000L;
        Account a0 = account("a0");
        Account a1 = account("a1");
        AccountPool pool = poolOf(a0, a1);
        pool.activeIndexByLane.put("lane", 0);

        int first = Selection.selectIndex(pool, "lane", now, Strategy.STICKY, null);
        assertEquals(0, first);
        assertEquals(0, pool.activeIndexByLane.get("lane")); // unchanged, still sticky at 0

        a0.rateLimitResetTimes = new LinkedHashMap<>();
        a0.rateLimitResetTimes.put("lane", now + 5000); // a0 becomes unavailable

        int second = Selection.selectIndex(pool, "lane", now, Strategy.STICKY, null);
        assertEquals(1, second);
        assertEquals(1, pool.activeIndexByLane.get("lane")); // cursor moved to a1
    }

    @Test
    void hybridReturnsSoonestFreeIndexWhenNoneCurrentlyAvailable() {
        long now = 1_000_000L;
        Account a0 = account("a0");
        Account a1 = account("a1");
        a0.rateLimitResetTimes = new LinkedHashMap<>();
        a0.rateLimitResetTimes.put("lane", now + 5000); // a0 frees up later
        a1.rateLimitResetTimes = new LinkedHashMap<>();
        a1.rateLimitResetTimes.put("lane", now + 2000); // a1 frees up sooner

        AccountPool pool = poolOf(a0, a1);
        pool.activeIndexByLane.put("lane", 0);

        int result = Selection.selectIndex(pool, "lane", now, Strategy.HYBRID, null);

        assertEquals(1, result); // a1 frees up sooner than a0, so hybrid claims it
        assertEquals(1, pool.activeIndexByLane.get("lane"));
    }

    @Test
    void stickyReturnsMinusOneWhenNoneAvailable() {
        long now = 1_000_000L;
        Account a0 = account("a0");
        a0.enabled = false;
        AccountPool pool = poolOf(a0);
        pool.activeIndexByLane.put("lane", 0);

        int result = Selection.selectIndex(pool, "lane", now, Strategy.STICKY, null);
        assertEquals(-1, result);
    }

    @Test
    void calculateBackoffMsWithoutJitterIsExactMinFormula() {
        assertEquals(8000L, RateLimitMath.calculateBackoffMs(3, 1000, 60000, false));
    }

    @Test
    void calculateBackoffMsClampsToMax() {
        assertEquals(60000L, RateLimitMath.calculateBackoffMs(10, 1000, 60000, false));
    }

    @Test
    void calculateBackoffMsWithJitterUsesInjectedRngDeterministically() {
        // raw = min(60000, 1000*2^3) = 8000; jittered = floor(8000/2 + rng*8000/2)
        long value = RateLimitMath.calculateBackoffMs(3, 1000, 60000, true, () -> 0.5);
        assertEquals(6000L, value); // floor(4000 + 0.5*4000) = 6000
    }

    @Test
    void isAvailableFalseWhenLaneRateLimited() {
        long now = 1_000_000L;
        Account a = account("a");
        a.rateLimitResetTimes = new LinkedHashMap<>();
        a.rateLimitResetTimes.put("lane", now + 1);
        org.junit.jupiter.api.Assertions.assertFalse(RateLimitMath.isAvailable(a, "lane", now));
        org.junit.jupiter.api.Assertions.assertTrue(RateLimitMath.isAvailable(a, "other-lane", now));
    }
}
