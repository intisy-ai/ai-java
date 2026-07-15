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
    void addTokenRejectsBlankEmailAndId() {
        AccountStore store = new AccountStore(new InMemoryStore(), new GsonJsonCodec());
        AccountAdmin admin = new AccountAdmin(store, () -> 1L);

        assertThrows(IllegalArgumentException.class,
                () -> admin.addToken("antigravity", null, null, "REFRESH", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> admin.addToken("antigravity", " ", " ", "REFRESH", null, null));
    }
}
