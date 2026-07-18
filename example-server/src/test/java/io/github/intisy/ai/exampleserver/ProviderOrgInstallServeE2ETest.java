package io.github.intisy.ai.exampleserver;

import io.github.intisy.ai.exampleserver.admin.AccountAdmin;
import io.github.intisy.ai.exampleserver.admin.MessagesAdmin;
import io.github.intisy.ai.exampleserver.api.ManagementApi;
import io.github.intisy.ai.exampleserver.discovery.ProviderDiscovery;
import io.github.intisy.ai.exampleserver.discovery.ProviderRegistryHolder;
import io.github.intisy.ai.exampleserver.discovery.ProviderSource;
import io.github.intisy.ai.jvm.AiJava;
import io.github.intisy.ai.jvm.Storage;
import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.Store;
import io.github.intisy.ai.shared.store.AccountStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 7 (part 1) e2e: proves the {@code install-from-org -> discover -> classload -> DIRECT
 * invoke} chain works with the REAL, out-of-band-built {@code antigravity-provider.jar}/{@code
 * claude-provider.jar} (staged at {@code exampleserver.orgProvidersDir}, default
 * {@code build/orgProviders} -- see {@code example-server/build.gradle}), mirroring {@link
 * ProviderInstallIntegrationTest} but with a real jar's own {@code Provider} discovered through a
 * dedicated {@link java.net.URLClassLoader} instead of the in-repo echo fixture.
 *
 * <p>Deterministic and network-free by design (see {@code .superpowers/sdd/phase-7-brief.md}): each
 * test posts straight to the just-installed provider's own {@code /api/providers/{id}/messages}
 * (console chat = a DIRECT {@link MessagesAdmin#send} call, never a router match -- there is no
 * model-&gt;provider resolution to seed here at all). NO account is ever seeded, so each provider's
 * own orchestrator takes its no-account branch and returns a hardcoded synthetic response WITHOUT
 * attempting any network call. That response is provider-specific and asserted against directly --
 * it is what proves the request reached the INSTALLED provider.
 *
 * <p>Skips (via {@link Assumptions}) when the two jars aren't staged -- e.g. in CI, where ai-java
 * cannot build the provider repos itself (see the interface map's §6 cross-repo constraint).
 */
class ProviderOrgInstallServeE2ETest {

    private static final String CLAUDE_MODEL = "claude-e2e";
    private static final String ANTIGRAVITY_MODEL = "antigravity-e2e";

    @TempDir
    Path configDir;

    @TempDir
    Path providersDir;

    private AiJava ai;
    private Store store;
    private JsonCodec json;
    private ProviderRegistryHolder holder;
    private ExampleServer server;

    @BeforeEach
    void setUp() {
        String orgProvidersDir = System.getProperty("exampleserver.orgProvidersDir");
        Path antigravityJar = orgProvidersDir != null ? Paths.get(orgProvidersDir, "antigravity-provider.jar") : null;
        Path claudeJar = orgProvidersDir != null ? Paths.get(orgProvidersDir, "claude-provider.jar") : null;
        Assumptions.assumeTrue(antigravityJar != null && claudeJar != null
                        && Files.exists(antigravityJar) && Files.exists(claudeJar),
                "org provider jars not staged at exampleserver.orgProvidersDir (" + orgProvidersDir + ")");

        // FILE store so HandlerCtx.configDir == configDir -- the installed provider's own
        // ClaudeBackend/AntigravityBackend.forConfigDir(ctx.configDir) opens a FileStore at the
        // SAME directory, converging on one accounts.json on disk (map §4b).
        ai = AiJava.builder().storage(Storage.file(configDir)).build();
        store = ai.store();
        json = ai.jsonCodec();

        holder = new ProviderRegistryHolder(ProviderDiscovery.resolve(providersDir));
        assertTrue(holder.listProviderIds().isEmpty(), "must start with zero providers loaded");

        AccountStore accountStore = new AccountStore(store, json);
        AccountAdmin admin = new AccountAdmin(accountStore, ai.clock());
        MessagesAdmin messages = new MessagesAdmin(store, json, holder, ai.logger());

        ProviderSource orgSource = new FakeOrgProviderSource(Paths.get(orgProvidersDir));
        ManagementApi api = new ManagementApi(holder::listProviderIds, admin, json, orgSource, providersDir, holder,
                null, null, null, null, null, null, null, null, messages);

        server = ExampleServer.start(0, api); // ephemeral port
    }

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) server.stop();
        if (holder != null && holder.get() != null) holder.get().close();
        if (ai != null) ai.close();
    }

    @Test
    void installRoutesToClaudeProvider() throws IOException {
        Response install = post("/api/providers/install", "{\"name\":\"claude\"}");
        assertEquals(200, install.status, install.body);
        assertTrue(install.body.contains("\"installed\":true") || install.body.contains("\"installed\": true"),
                install.body);
        assertTrue(install.body.contains("claude"), install.body);

        Response afterList = get("/api/providers");
        assertEquals(200, afterList.status);
        assertTrue(afterList.body.contains("\"claude\""), afterList.body);

        // /api/providers/available lists every FakeOrgProviderSource entry regardless of install
        // state, so a plain "contains claude" check is tautological -- assert the entry's OWN
        // installed flag instead, which ManagementApi#handleAvailable only sets true once the
        // jar is actually on disk (Files.exists(providersDir.resolve(entry.assetName))).
        Response available = get("/api/providers/available");
        assertEquals(200, available.status);
        assertEquals(Boolean.TRUE, availableEntry(available.body, "claude").get("installed"), available.body);

        // No account seeded for claude -> zero enabled accounts -> the claude orchestrator's OWN
        // no-account synthetic (ClaudeHandleOrchestratorTest/ClaudeProviderTest:
        // noAccountConfigured_returnsSyntheticInvalidRequestError): 400, x-hub-chat-error: 1,
        // an invalid_request_error body -- proving the DIRECT call reached the INSTALLED provider
        // (console chat, POST /api/providers/{id}/messages, never a router match).
        String body = "{\"model\":\"" + CLAUDE_MODEL + "\",\"messages\":[]}";
        Response messages = post("/api/providers/claude/messages", body);
        assertEquals(400, messages.status, messages.body);
        assertEquals("1", messages.headers.get("x-hub-chat-error"), messages.body);
        assertTrue(messages.body.contains("invalid_request_error"), messages.body);
    }

    @Test
    void installRoutesToAntigravityProvider() throws IOException {
        Response install = post("/api/providers/install", "{\"name\":\"antigravity\"}");
        assertEquals(200, install.status, install.body);
        assertTrue(install.body.contains("\"installed\":true") || install.body.contains("\"installed\": true"),
                install.body);
        assertTrue(install.body.contains("antigravity"), install.body);

        Response afterList = get("/api/providers");
        assertEquals(200, afterList.status);
        assertTrue(afterList.body.contains("\"antigravity\""), afterList.body);

        // See installRoutesToClaudeProvider for why this checks the entry's OWN installed flag
        // rather than a tautological body.contains("antigravity").
        Response available = get("/api/providers/available");
        assertEquals(200, available.status);
        assertEquals(Boolean.TRUE, availableEntry(available.body, "antigravity").get("installed"), available.body);

        // No account seeded for antigravity -> AccountManager#acquire returns null ->
        // AntigravityHandleOrchestrator#attemptModel's own "no available account" branch
        // (AntigravityProviderTest.handle_noAccountConfigured_returnsClearErrorWithoutNetworkCall):
        // synthesized 503 (isRateLimitStatus(503) == true, so AntigravityProvider re-wraps it as a
        // rate_limit_error), body mentioning "No available antigravity account" -- the provider's
        // OWN wording, proving the DIRECT call reached the INSTALLED provider (console chat, POST
        // /api/providers/{id}/messages, never a router match).
        String body = "{\"model\":\"" + ANTIGRAVITY_MODEL + "\",\"messages\":[]}";
        Response messages = post("/api/providers/antigravity/messages", body);
        assertEquals(503, messages.status, messages.body);
        assertTrue(messages.body.contains("No available antigravity account"), messages.body);
        assertNull(messages.headers.get("x-hub-chat-error"), messages.body);
    }

    @Test
    void pasteTokenSeedIsVisibleOnDiskForProvider() throws IOException {
        Response install = post("/api/providers/install", "{\"name\":\"claude\"}");
        assertEquals(200, install.status, install.body);

        Response add = post("/api/providers/claude/accounts",
                "{\"refresh\":\"raw-refresh-xyz\",\"email\":\"e2e@x.com\"}");
        assertEquals(200, add.status, add.body);
        assertFalse(add.body.contains("raw-refresh-xyz"), "the admin view must never echo the raw refresh token");

        // AccountStore's on-disk key is "accounts.json" directly under configFolder (see
        // shared/store/AccountStore.KEY) -- the EXACT file ClaudeBackend.forConfigDir(configDir)'s
        // own FileStore(Paths.get(configDir)) would open, proving the installed provider (a
        // separate FileStore instance, same directory) would see this seeded account.
        Path accountsFile = configDir.resolve("accounts.json");
        assertTrue(Files.exists(accountsFile), "accounts.json must be written under the shared configDir");
        String raw = new String(Files.readAllBytes(accountsFile), StandardCharsets.UTF_8);

        Map<String, Object> account = accountFor(raw, "claude");
        assertEquals("raw-refresh-xyz", account.get("refresh"), "refresh must be stored RAW, not packed");
        assertEquals(Boolean.TRUE, account.get("enabled"));
    }

    /** Parses the {@code /api/providers/available} JSON array and returns the entry whose
     *  {@code name} matches, so callers can assert its {@code installed} flag directly. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> availableEntry(String rawAvailableJson, String name) {
        List<Object> entries = (List<Object>) json.parse(rawAvailableJson);
        for (Object o : entries) {
            Map<String, Object> entry = (Map<String, Object>) o;
            if (name.equals(entry.get("name"))) {
                return entry;
            }
        }
        throw new AssertionError("no /api/providers/available entry named \"" + name + "\": " + rawAvailableJson);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> accountFor(String rawAccountsJson, String providerId) {
        Object parsed = json.parse(rawAccountsJson);
        Map<String, Object> doc = (Map<String, Object>) parsed;
        Map<String, Object> providers = (Map<String, Object>) doc.get("providers");
        Map<String, Object> entry = (Map<String, Object>) providers.get(providerId);
        List<Object> accounts = (List<Object>) entry.get("accounts");
        assertEquals(1, accounts.size(), rawAccountsJson);
        return (Map<String, Object>) accounts.get(0);
    }


    /** Simulates a real org download with no network: copies whichever of the two REAL,
     *  pre-built provider jars (staged at {@code exampleserver.orgProvidersDir}) matches the
     *  requested entry's asset name into the install target dir. */
    private static final class FakeOrgProviderSource implements ProviderSource {
        private final Path orgProvidersDir;

        FakeOrgProviderSource(Path orgProvidersDir) {
            this.orgProvidersDir = orgProvidersDir;
        }

        @Override
        public List<Entry> list() {
            return Arrays.asList(
                    new Entry("claude", "claude-provider.jar", "", "1.0.0"),
                    new Entry("antigravity", "antigravity-provider.jar", "", "1.0.0"));
        }

        @Override
        public Entry find(String name) {
            for (Entry entry : list()) {
                if (entry.name.equals(name)) return entry;
            }
            return null;
        }

        @Override
        public Path download(Entry entry, Path dir) throws IOException {
            Path src = orgProvidersDir.resolve(entry.assetName);
            if (!Files.exists(src)) {
                throw new IOException("staged org provider jar not found: " + src);
            }
            Path target = dir.resolve(entry.assetName);
            Files.copy(src, target);
            return target;
        }
    }

    // -- tiny loopback HTTP client (test-only; newer JDK APIs allowed in tests) --
    // Extends ProviderInstallIntegrationTest's helper with response headers, needed to assert
    // claude's x-hub-chat-error marker.

    private Response get(String path) throws IOException {
        HttpURLConnection c = open(path, "GET");
        return read(c);
    }

    private Response post(String path, String body) throws IOException {
        HttpURLConnection c = open(path, "POST");
        c.setDoOutput(true);
        c.setRequestProperty("content-type", "application/json");
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return read(c);
    }

    private HttpURLConnection open(String path, String method) throws IOException {
        URL url = new URL("http://127.0.0.1:" + server.port() + path);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod(method);
        return c;
    }

    private Response read(HttpURLConnection c) throws IOException {
        int status = c.getResponseCode();
        InputStream is = status < 400 ? c.getInputStream() : c.getErrorStream();
        String text = "";
        if (is != null) {
            try (Scanner s = new Scanner(is, "UTF-8").useDelimiter("\\A")) {
                text = s.hasNext() ? s.next() : "";
            }
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
            if (e.getKey() != null && e.getValue() != null && !e.getValue().isEmpty()) {
                headers.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().get(0));
            }
        }
        return new Response(status, text, headers);
    }

    private static final class Response {
        final int status;
        final String body;
        final Map<String, String> headers;

        Response(int status, String body, Map<String, String> headers) {
            this.status = status;
            this.body = body;
            this.headers = headers;
        }
    }
}
