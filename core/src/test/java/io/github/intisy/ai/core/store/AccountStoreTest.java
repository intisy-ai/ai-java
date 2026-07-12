package io.github.intisy.ai.core.store;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
