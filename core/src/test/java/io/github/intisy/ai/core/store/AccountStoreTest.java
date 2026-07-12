package io.github.intisy.ai.core.store;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountStoreTest {
    @Test
    void addListAndUpsertRoundTripExactJsonShape() throws Exception {
        Path cf = Files.createTempDirectory("ai-store");
        AccountStore s = new AccountStore(cf);

        Account a = new Account();
        a.id = "acc1";
        a.refresh = "r1";
        a.enabled = true;
        s.add("claude-code", a);

        assertEquals(1, s.list("claude-code").size());

        String json = Files.readString(cf.resolve("accounts.json"));
        assertTrue(json.contains("\"version\""));
        assertTrue(json.contains("\"providers\"") && json.contains("\"claude-code\""));

        Account a2 = new Account();
        a2.id = "acc1";
        a2.refresh = "r1b";
        s.add("claude-code", a2); // upsert by id

        assertEquals(1, s.list("claude-code").size());
        assertEquals("r1b", s.list("claude-code").get(0).refresh);
    }

    /**
     * Locks the gson LONG_OR_DOUBLE fix: a whole-number {@code meta} entry (e.g. a lane's
     * remaining-quota count) must round-trip through the store WITHOUT gaining a spurious
     * trailing {@code .0}, while a genuinely fractional entry (e.g. {@code remainingFraction})
     * must still serialize as a JSON double. Without setObjectToNumberStrategy(LONG_OR_DOUBLE),
     * Gson's default ToNumberPolicy.DOUBLE would turn {@code "count":5} into {@code "count":5.0}
     * on every write, corrupting byte-compatibility with the JS core-auth store.
     */
    @Test
    void meta_wholeNumberSurvivesRoundTripWithoutTrailingZero() throws Exception {
        Path cf = Files.createTempDirectory("ai-store-meta");
        AccountStore s = new AccountStore(cf);

        Account a = new Account();
        a.id = "acc-meta";
        a.refresh = "r-meta";
        a.enabled = true;
        a.meta = new LinkedHashMap<>();
        a.meta.put("count", 5L);
        a.meta.put("remainingFraction", 0.5);
        s.add("meta-provider", a);

        Account roundTripped = s.list("meta-provider").get(0);
        assertEquals(5.0, ((Number) roundTripped.meta.get("count")).doubleValue());
        assertEquals(0.5, ((Number) roundTripped.meta.get("remainingFraction")).doubleValue());

        String json = Files.readString(cf.resolve("accounts.json"));
        assertTrue(json.contains("\"count\": 5"));
        assertFalse(json.contains("\"count\": 5.0"));
        assertTrue(json.contains("\"remainingFraction\": 0.5"));
    }
}
