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
}
