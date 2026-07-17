package io.github.intisy.ai.exampleserver;

import io.github.intisy.ai.exampleserver.admin.AccountAdmin;
import io.github.intisy.ai.exampleserver.admin.OAuthAdmin;
import io.github.intisy.ai.exampleserver.discovery.ProviderDiscovery;
import io.github.intisy.ai.exampleserver.discovery.ProviderRegistryHolder;
import io.github.intisy.ai.jvm.backend.json.GsonJsonCodec;
import io.github.intisy.ai.jvm.backend.store.InMemoryStore;
import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.Store;
import io.github.intisy.ai.shared.store.AccountStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Exercises {@link OAuthAdmin} against the same REAL jar-discovered {@code echo} provider
 * {@link ConfigAdminTest}/{@link QuotaAdminTest} use, staged the same way. {@code EchoProvider}
 * answers {@code GET /v1/oauth/params} + {@code POST /v1/oauth/exchange} (added alongside this
 * task), giving a real full authorize-url + state/PKCE + callback round-trip with no network
 * involved.
 */
class OAuthAdminTest {

    private Store store;
    private JsonCodec json;
    private ProviderRegistryHolder holder;
    private AccountStore accountStore;
    private OAuthAdmin oauth;

    @BeforeEach
    void setUp(@TempDir Path providersDir) throws IOException {
        store = new InMemoryStore();
        json = new GsonJsonCodec();

        stageProviderJar(providersDir);
        holder = new ProviderRegistryHolder(ProviderDiscovery.resolve(providersDir));
        assertTrue(holder.listProviderIds().contains("echo"), holder.listProviderIds().toString());

        accountStore = new AccountStore(store, json);
        AccountAdmin admin = new AccountAdmin(accountStore, () -> 1000L);
        oauth = new OAuthAdmin(store, json, holder, msg -> { }, admin, () -> 1000L);
    }

    @AfterEach
    void tearDown() throws IOException {
        // Releases the URLClassLoader backing the jar copied into @TempDir, or its cleanup fails
        // on Windows (file still in use) -- same reasoning as RoutingAdminTest/QuotaAdminTest.
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
    void startReturnsAuthorizeUrlWithStateAndChallenge() {
        Map<String, Object> result = oauth.start("echo", "http://127.0.0.1:9999");
        String url = (String) result.get("authorizeUrl");
        assertNotNull(url);
        assertTrue(url.contains("echo-client-id"), url);           // provider's public client id
        assertTrue(url.contains("code_challenge="), url);
        assertTrue(url.contains("code_challenge_method=S256"), url);
        assertTrue(url.contains("state="), url);
        assertNotNull(result.get("state"));
    }

    @Test
    void callbackWithValidStateSeedsAccount() {
        Map<String, Object> started = oauth.start("echo", "http://127.0.0.1:9999");
        String state = (String) started.get("state");

        Map<String, Object> result = oauth.callback("auth-code-xyz", state);
        Object account = result.get("account");
        assertNotNull(account);
        assertEquals(1, accountStore.list("echo").size());
    }

    @Test
    void callbackWithUnknownStateThrows() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> oauth.callback("code", "not-a-real-state"));
        assertTrue(e.getMessage().toLowerCase().contains("state"), e.getMessage());
    }

    @Test
    void startUnknownProviderThrows() {
        assertThrows(IllegalArgumentException.class, () -> oauth.start("nope", "http://x"));
    }

    @Test
    void callbackReplayOfSameStateIsRejected() {
        Map<String, Object> started = oauth.start("echo", "http://127.0.0.1:9999");
        String state = (String) started.get("state");

        oauth.callback("auth-code-xyz", state); // first use succeeds

        // Single-use: the same state must not be redeemable a second time (replay).
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> oauth.callback("auth-code-xyz", state));
        assertTrue(e.getMessage().toLowerCase().contains("state"), e.getMessage());
    }

    @Test
    void callbackWithExpiredStateIsRejectedAndSeedsNoAccount() {
        // Separate OAuthAdmin with an advanceable clock so the fixed-clock instance used by the
        // other tests in this class is left undisturbed.
        long[] now = {1000L};
        AccountAdmin admin = new AccountAdmin(accountStore, () -> now[0]);
        OAuthAdmin expiring = new OAuthAdmin(store, json, holder, msg -> { }, admin, () -> now[0]);

        Map<String, Object> started = expiring.start("echo", "http://127.0.0.1:9999");
        String state = (String) started.get("state");

        now[0] += 10 * 60 * 1000L + 1; // advance past the 10-minute pending TTL

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> expiring.callback("auth-code-xyz", state));
        assertTrue(e.getMessage().toLowerCase().contains("expired"), e.getMessage());
        assertEquals(0, accountStore.list("echo").size());
    }
}
