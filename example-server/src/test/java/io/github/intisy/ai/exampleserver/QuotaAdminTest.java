package io.github.intisy.ai.exampleserver;

import io.github.intisy.ai.exampleserver.admin.QuotaAdmin;
import io.github.intisy.ai.exampleserver.discovery.ProviderDiscovery;
import io.github.intisy.ai.exampleserver.discovery.ProviderRegistryHolder;
import io.github.intisy.ai.jvm.backend.json.GsonJsonCodec;
import io.github.intisy.ai.jvm.backend.store.InMemoryStore;
import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.Store;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Exercises {@link QuotaAdmin} against the same REAL jar-discovered provider pair
 * {@link RoutingAdminTest} uses (echo + ratelimited), staged the same way. {@code EchoProvider}
 * answers {@code GET /v1/quota} with a canned accounts/quota body (added alongside this task) and
 * {@code AlwaysRateLimitedProvider} always 429s, giving a real 2xx and a real non-2xx quota
 * response with no network involved.
 */
class QuotaAdminTest {

    private Store store;
    private JsonCodec json;
    private ProviderRegistryHolder holder;
    private QuotaAdmin quota;

    @BeforeEach
    void setUp(@TempDir Path providersDir) throws IOException {
        store = new InMemoryStore();
        json = new GsonJsonCodec();

        stageProviderJar(providersDir);
        holder = new ProviderRegistryHolder(ProviderDiscovery.resolve(providersDir));
        assertTrue(holder.listProviderIds().contains("echo"), holder.listProviderIds().toString());
        assertTrue(holder.listProviderIds().contains("ratelimited"), holder.listProviderIds().toString());

        quota = new QuotaAdmin(store, json, holder, msg -> { });
    }

    @AfterEach
    void tearDown() throws IOException {
        // Releases the URLClassLoader backing the jar copied into @TempDir, or its cleanup fails
        // on Windows (file still in use) -- same reasoning as RoutingAdminTest.
        if (holder != null && holder.get() != null) holder.get().close();
    }

    private static void stageProviderJar(Path targetDir) throws IOException {
        String staged = System.getProperty("exampleserver.providersDir");
        assertNotNull(staged, "exampleserver.providersDir must be set by the Gradle test task");
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(Path.of(staged), "*.jar")) {
            for (Path jar : stream) {
                Files.copy(jar, targetDir.resolve(jar.getFileName()));
                return;
            }
        }
        fail("no staged provider jar found in " + staged);
    }

    @Test
    void refreshReturnsParsedAccountsAndQuota() {
        Map<String, Object> result = quota.refresh("echo");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> accounts = (List<Map<String, Object>>) result.get("accounts");
        assertEquals(3, accounts.size());
        assertEquals("a1", accounts.get(0).get("id"));
        assertNotNull(accounts.get(0).get("quota"));
    }

    @Test
    void refreshUnknownProviderThrows() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> quota.refresh("nope"));
        assertTrue(e.getMessage().contains("unknown provider: nope"), e.getMessage());
    }

    @Test
    void refreshNon2xxThrowsWithProviderMessage() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> quota.refresh("ratelimited"));
        assertTrue(e.getMessage().contains("provider returned 429"), e.getMessage());
        assertTrue(e.getMessage().contains("rate_limit_error"), e.getMessage());
    }

    @Test
    void combinedAggregatesMeanRemainingFractionPerLabelAndCountsErrorAccounts() {
        // Echo's canned /v1/quota (EchoProvider.quotaResponse): a1+a2 share "5-hour"
        // (0.8, 0.4 -> mean 0.6), a3 is an errored account with no quota at all.
        Map<String, Object> result = quota.combined("echo");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> accounts = (List<Map<String, Object>>) result.get("accounts");
        assertEquals(3, accounts.size(), "raw accounts array must still be present, unchanged");

        @SuppressWarnings("unchecked")
        Map<String, Object> combined = (Map<String, Object>) result.get("combined");
        assertNotNull(combined);
        assertEquals(3, combined.get("accountCount"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pools = (List<Map<String, Object>>) combined.get("pools");
        assertEquals(1, pools.size());
        Map<String, Object> fiveHour = pools.get(0);
        assertEquals("5-hour", fiveHour.get("label"));
        assertEquals(0.6, (double) (Number) fiveHour.get("remainingFraction"), 1e-9);
        assertEquals(2, fiveHour.get("accounts"));
    }
}
