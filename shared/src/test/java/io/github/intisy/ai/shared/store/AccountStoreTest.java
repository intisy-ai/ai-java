package io.github.intisy.ai.shared.store;

import io.github.intisy.ai.shared.model.Account;
import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.Store;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountStoreTest {

    @Test
    void addListAndUpsertRoundTripExactJsonShape() {
        Store store = new InMemoryStore();
        JsonCodec json = new TestJsonCodec();
        AccountStore s = new AccountStore(store, json);

        Account a = new Account();
        a.id = "acc1";
        a.refresh = "r1";
        a.enabled = true;
        s.add("claude-code", a);

        assertEquals(1, s.list("claude-code").size());

        String raw = store.get("accounts.json");
        assertNotNull(raw);
        assertTrue(raw.contains("\"version\""));
        assertTrue(raw.contains("\"providers\"") && raw.contains("\"claude-code\""));

        Account a2 = new Account();
        a2.id = "acc1";
        a2.refresh = "r1b";
        s.add("claude-code", a2); // upsert by id

        assertEquals(1, s.list("claude-code").size());
        assertEquals("r1b", s.list("claude-code").get(0).refresh);
    }

    @Test
    void addUpsertsByRefreshWhenIdDiffersOrMissing() {
        Store store = new InMemoryStore();
        AccountStore s = new AccountStore(store, new TestJsonCodec());

        Account a = new Account();
        a.refresh = "same-refresh";
        a.email = "old@example.com";
        s.add("prov", a);

        Account a2 = new Account();
        a2.refresh = "same-refresh";
        a2.email = "new@example.com";
        s.add("prov", a2);

        assertEquals(1, s.list("prov").size());
        assertEquals("new@example.com", s.list("prov").get(0).email);
    }

    @Test
    void removeDropsAccountById() {
        Store store = new InMemoryStore();
        AccountStore s = new AccountStore(store, new TestJsonCodec());

        Account a = new Account();
        a.id = "acc1";
        s.add("prov", a);
        assertEquals(1, s.list("prov").size());

        s.remove("prov", "acc1");
        assertEquals(0, s.list("prov").size());
    }

    /**
     * Locks numeric fidelity: a whole-number {@code meta} entry (e.g. a lane's remaining-quota
     * count) must round-trip through the store WITHOUT gaining a spurious trailing {@code .0},
     * while a genuinely fractional entry (e.g. {@code remainingFraction}) must still serialize
     * as a JSON double. In shared code this means building whole numbers as {@code Long}/
     * {@code Integer} (not {@code Double}) so the codec sees the right type.
     */
    @Test
    void meta_wholeNumberSurvivesRoundTripWithoutTrailingZero() {
        Store store = new InMemoryStore();
        AccountStore s = new AccountStore(store, new TestJsonCodec());

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

        String raw = store.get("accounts.json");
        assertTrue(raw.contains("\"count\":5"));
        assertFalse(raw.contains("\"count\":5.0"));
        assertTrue(raw.contains("\"remainingFraction\":0.5"));
    }

    /**
     * Best-effort read resilience (JS/core parity): a corrupted {@code accounts.json} must
     * degrade to an empty pool rather than throwing out of {@code list}/{@code load}.
     */
    @Test
    void list_returnsEmptyPoolWhenStoreContainsMalformedJson() {
        Store store = new InMemoryStore();
        store.put("accounts.json", "{ not json");
        AccountStore s = new AccountStore(store, new TestJsonCodec());

        assertTrue(s.list("claude-code").isEmpty());
    }
}
