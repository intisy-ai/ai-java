package io.github.intisy.ai.exampleserver;

import io.github.intisy.ai.exampleserver.admin.AccountAdmin;
import io.github.intisy.ai.jvm.backend.json.GsonJsonCodec;
import io.github.intisy.ai.jvm.backend.store.InMemoryStore;
import io.github.intisy.ai.shared.model.Account;
import io.github.intisy.ai.shared.store.AccountStore;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AccountAdminTest {
    private static Account acct(String id, boolean enabled) {
        Account a = new Account(); a.id = id; a.email = id; a.enabled = enabled; return a;
    }

    @Test
    void listsStatusesAndHidesSecrets() {
        AccountStore store = new AccountStore(new InMemoryStore(), new GsonJsonCodec());
        Account ready = acct("a@x", true);
        Account limited = acct("b@x", true);
        limited.rateLimitResetTimes = new HashMap<>();
        limited.rateLimitResetTimes.put("messages", 10_000L);
        store.add("echo", ready); store.add("echo", limited);

        AccountAdmin admin = new AccountAdmin(store, () -> 5_000L); // now=5000 < 10000 => rate-limited
        List<AccountAdmin.AccountView> views = admin.list("echo");
        assertEquals(2, views.size());
        assertEquals("ready", views.stream().filter(v -> v.id.equals("a@x")).findFirst().get().status);
        assertEquals("rate-limited", views.stream().filter(v -> v.id.equals("b@x")).findFirst().get().status);

        admin.setEnabled("echo", "a@x", false);
        assertEquals("disabled", admin.list("echo").stream().filter(v -> v.id.equals("a@x")).findFirst().get().status);
        admin.remove("echo", "b@x");
        assertEquals(1, admin.list("echo").size());
    }

    @Test
    void exposesNonSecretAccessExpiryAsEpochMsNullWhenAbsent() {
        AccountStore store = new AccountStore(new InMemoryStore(), new GsonJsonCodec());
        Account withExpiry = acct("a@x", true);
        withExpiry.expires = 999_000L;
        Account withoutExpiry = acct("b@x", true);
        store.add("echo", withExpiry);
        store.add("echo", withoutExpiry);

        AccountAdmin admin = new AccountAdmin(store, () -> 5_000L);
        List<AccountAdmin.AccountView> views = admin.list("echo");

        assertEquals(999_000L, views.stream().filter(v -> v.id.equals("a@x")).findFirst().get().expires);
        assertNull(views.stream().filter(v -> v.id.equals("b@x")).findFirst().get().expires);
    }

    @Test
    void addTokenSeedsFlagCAccountShapeAndReturnsSecretFreeView() {
        AccountStore store = new AccountStore(new InMemoryStore(), new GsonJsonCodec());
        AccountAdmin admin = new AccountAdmin(store, () -> 42_000L);

        AccountAdmin.AccountView view = admin.addToken(
                "antigravity", null, "a@b.com", "REFRESH123", "proj-1", "managed-9");

        assertEquals("a@b.com", view.id);
        assertEquals("a@b.com", view.email);
        assertEquals("ready", view.status);
        assertTrue(view.enabled);

        List<AccountAdmin.AccountView> views = admin.list("antigravity");
        assertEquals(1, views.size());
        assertEquals("a@b.com", views.get(0).id);

        Account raw = store.list("antigravity").get(0);
        assertEquals("REFRESH123", raw.refresh); // RAW, never packed as "token|projectId"
        assertEquals("proj-1", raw.meta.get("projectId"));
        assertEquals("managed-9", raw.meta.get("managedProjectId"));
        assertTrue(raw.enabled);
        assertNotNull(raw.addedAt);
        assertEquals(42_000L, raw.addedAt);
    }

    @Test
    void addTokenDefaultsIdToEmailWhenIdBlank() {
        AccountStore store = new AccountStore(new InMemoryStore(), new GsonJsonCodec());
        AccountAdmin admin = new AccountAdmin(store, () -> 1L);

        admin.addToken("antigravity", "", "a@b.com", "REFRESH", null, null);

        Account raw = store.list("antigravity").get(0);
        assertEquals("a@b.com", raw.id);
        assertEquals("a@b.com", raw.email);
        assertTrue(raw.meta == null || raw.meta.isEmpty());
    }

    @Test
    void addTokenDefaultsEmailToIdWhenEmailBlank() {
        AccountStore store = new AccountStore(new InMemoryStore(), new GsonJsonCodec());
        AccountAdmin admin = new AccountAdmin(store, () -> 1L);

        admin.addToken("antigravity", "acc-id", null, "REFRESH", null, null);

        Account raw = store.list("antigravity").get(0);
        assertEquals("acc-id", raw.id);
        assertEquals("acc-id", raw.email);
    }

    @Test
    void addTokenRejectsBlankRefresh() {
        AccountStore store = new AccountStore(new InMemoryStore(), new GsonJsonCodec());
        AccountAdmin admin = new AccountAdmin(store, () -> 1L);

        assertThrows(IllegalArgumentException.class,
                () -> admin.addToken("antigravity", "id", "e@x.com", "  ", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> admin.addToken("antigravity", "id", "e@x.com", null, null, null));
    }

    @Test
    void addTokenReturnsPersistedStatusNotStaleLocalStatusOnUpsert() {
        AccountStore store = new AccountStore(new InMemoryStore(), new GsonJsonCodec());
        AccountAdmin admin = new AccountAdmin(store, () -> 1_000L);

        // Seed the account, then independently drive it into a cooling state (as a real
        // rate-limit/backoff handler would, via AccountStore, not via AccountAdmin).
        admin.addToken("antigravity", "acc-1", "a@b.com", "REFRESH-OLD", null, null);
        store.update("antigravity", pool -> {
            for (Account a : pool.accounts) {
                if ("acc-1".equals(a.id)) {
                    a.coolingDownUntil = 10_000L; // still cooling relative to clock=1_000
                }
            }
        });

        // Re-seeding (e.g. pasting a fresh token) upserts by id: AccountStore.add merges into
        // the existing record and PRESERVES coolingDownUntil, so the persisted account is still
        // cooling even though the freshly-built local Account object looks fully "ready".
        AccountAdmin.AccountView view = admin.addToken(
                "antigravity", "acc-1", "a@b.com", "REFRESH-NEW", null, null);

        assertEquals("cooling", view.status, "must reflect the PERSISTED (merged) record, not the local pre-merge object");

        Account raw = store.list("antigravity").get(0);
        assertEquals("REFRESH-NEW", raw.refresh);
        assertEquals(10_000L, raw.coolingDownUntil);
    }

    @Test
    void addTokenTrimsWhitespaceFromResolvedIdAndEmail() {
        AccountStore store = new AccountStore(new InMemoryStore(), new GsonJsonCodec());
        AccountAdmin admin = new AccountAdmin(store, () -> 1L);

        admin.addToken("antigravity", " acc-1 ", " a@b.com ", "REFRESH", null, null);

        Account raw = store.list("antigravity").get(0);
        assertEquals("acc-1", raw.id);
        assertEquals("a@b.com", raw.email);
    }

    @Test
    void addTokenRejectsBlankEmailAndId() {
        AccountStore store = new AccountStore(new InMemoryStore(), new GsonJsonCodec());
        AccountAdmin admin = new AccountAdmin(store, () -> 1L);

        assertThrows(IllegalArgumentException.class,
                () -> admin.addToken("antigravity", null, null, "REFRESH", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> admin.addToken("antigravity", " ", " ", "REFRESH", null, null));
    }
}
