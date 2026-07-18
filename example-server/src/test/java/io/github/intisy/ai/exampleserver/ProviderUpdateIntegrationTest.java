package io.github.intisy.ai.exampleserver;

import io.github.intisy.ai.exampleserver.admin.AccountAdmin;
import io.github.intisy.ai.exampleserver.api.ManagementApi;
import io.github.intisy.ai.exampleserver.discovery.InstalledVersions;
import io.github.intisy.ai.exampleserver.discovery.ProviderDiscovery;
import io.github.intisy.ai.exampleserver.discovery.ProviderRegistryHolder;
import io.github.intisy.ai.exampleserver.discovery.ProviderSource;
import io.github.intisy.ai.jvm.AiJava;
import io.github.intisy.ai.jvm.Storage;
import io.github.intisy.ai.jvm.provider.ProviderRegistry;
import io.github.intisy.ai.shared.model.Account;
import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.Store;
import io.github.intisy.ai.shared.store.AccountStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Drives the update-detection + update-install feature end to end (no network): {@code
 * /api/providers/available} reporting {@code updateAvailable} from a {@code .version} sidecar
 * vs. the org scan's version, and {@code POST /api/providers/{id}/update} performing the
 * Windows-safe close-registry-first-then-overwrite-then-refresh sequence (mirroring how {@code
 * ProviderRegistryHolderTest} proves uninstall's close-before-delete). Each test builds its own
 * self-contained server/holder so the different fixtures (sidecar present/absent/equal, source
 * with/without a matching entry) never leak into each other.
 */
class ProviderUpdateIntegrationTest {

    private static final String CONFIG_FILE = "provider-update-routing.json";

    /** Copies the single staged echo-provider jar (built by the Gradle test task) to {@code
     *  targetDir/echo-provider.jar} so {@code ProviderDiscovery.resolve} discovers provider id
     *  {@code "echo"}. */
    private static Path stageEchoJar(Path targetDir) throws IOException {
        String staged = System.getProperty("exampleserver.providersDir");
        assertTrue(staged != null, "exampleserver.providersDir must be set by the Gradle test task");
        Path target = targetDir.resolve("echo-provider.jar");
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(Path.of(staged), "*.jar")) {
            for (Path jar : stream) {
                Files.copy(jar, target);
                return target;
            }
        }
        fail("no staged provider jar found in " + staged);
        return null; // unreachable
    }

    /** Copies the staged echo-provider jar to {@code targetDir/assetName} (an arbitrary file name)
     *  so the discovered provider id ({@code "echo"}, read from the jar content, NOT its file name)
     *  can differ from the repo/asset name -- mirroring real providers like stub-auth (id
     *  {@code "stub"}, asset {@code "stub-auth-provider.jar"}). */
    private static Path stageEchoJarAs(Path targetDir, String assetName) throws IOException {
        String staged = System.getProperty("exampleserver.providersDir");
        assertTrue(staged != null, "exampleserver.providersDir must be set by the Gradle test task");
        Path target = targetDir.resolve(assetName);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(Path.of(staged), "*.jar")) {
            for (Path jar : stream) {
                Files.copy(jar, target);
                return target;
            }
        }
        fail("no staged provider jar found in " + staged);
        return null; // unreachable
    }

    /** A fake org source whose {@code Entry} carries a repo {@code name} distinct from the provider
     *  id, resolving only by its {@code assetName} -- proves the update path maps a provider id to
     *  its org entry via the installed jar's asset name, not by assuming id == repo name. */
    private static final class MismatchedNameProviderSource implements ProviderSource {
        private final Path stagedJarDir;
        private final String repoName;
        private final String assetName;
        private final String version;

        MismatchedNameProviderSource(Path stagedJarDir, String repoName, String assetName, String version) {
            this.stagedJarDir = stagedJarDir;
            this.repoName = repoName;
            this.assetName = assetName;
            this.version = version;
        }

        @Override
        public List<Entry> list() {
            return Collections.singletonList(new Entry(repoName, assetName, "", version));
        }

        @Override
        public Entry find(String name) {
            return repoName.equals(name) ? new Entry(repoName, assetName, "", version) : null;
        }

        @Override
        public Path download(Entry entry, Path dir) throws IOException {
            Path target = dir.resolve(entry.assetName);
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(stagedJarDir, "*.jar")) {
                for (Path p : stream) {
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                    InstalledVersions.write(target, entry.version);
                    return target;
                }
            }
            throw new IOException("no staged provider jar found in " + stagedJarDir);
        }
    }

    /** A fake org source that always resolves the single {@code "echo"} entry at {@code
     *  version}, and whose {@code download} mirrors the real {@code GithubOrgScan.download}:
     *  writes the jar AND its {@code .version} sidecar. */
    private static final class FakeVersionedProviderSource implements ProviderSource {
        private final Path stagedJarDir;
        private final String assetName;
        private final String version;

        FakeVersionedProviderSource(Path stagedJarDir, String assetName, String version) {
            this.stagedJarDir = stagedJarDir;
            this.assetName = assetName;
            this.version = version;
        }

        @Override
        public List<Entry> list() {
            return Collections.singletonList(new Entry("echo", assetName, "", version));
        }

        @Override
        public Entry find(String name) {
            return "echo".equals(name) ? new Entry("echo", assetName, "", version) : null;
        }

        @Override
        public Path download(Entry entry, Path dir) throws IOException {
            Path target = dir.resolve(entry.assetName);
            Files.copy(findStagedJar(), target, StandardCopyOption.REPLACE_EXISTING);
            InstalledVersions.write(target, entry.version);
            return target;
        }

        private Path findStagedJar() throws IOException {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(stagedJarDir, "*.jar")) {
                for (Path p : stream) return p;
            }
            throw new IOException("no staged provider jar found in " + stagedJarDir);
        }
    }

    /** Simulates an org repo that no longer resolves (e.g. deleted/renamed upstream): both {@code
     *  list()} and {@code find()} always come back empty/null. */
    private static final class NoEntriesProviderSource implements ProviderSource {
        @Override
        public List<Entry> list() {
            return Collections.emptyList();
        }

        @Override
        public Entry find(String name) {
            return null;
        }

        @Override
        public Path download(Entry entry, Path dir) {
            throw new UnsupportedOperationException("never called in this fixture");
        }
    }

    private static Account account(String id, String email) {
        Account a = new Account();
        a.id = id;
        a.email = email;
        a.enabled = true;
        return a;
    }

    // -- /api/providers/available: updateAvailable computation --

    @Test
    void availableReportsUpdateAvailableWhenSidecarDiffersFromScanVersion(@TempDir Path providersDir) throws Exception {
        Path jar = stageEchoJar(providersDir);
        InstalledVersions.write(jar, "1.0.0"); // simulates a prior install at v1.0.0

        AiJava ai = AiJava.builder().storage(Storage.memory()).build();
        ServerSeeds.seedEcho(ai.store(), ai.jsonCodec(), CONFIG_FILE);
        ProviderRegistryHolder holder = new ProviderRegistryHolder(ProviderDiscovery.resolve(providersDir));
        assertTrue(holder.listProviderIds().contains("echo"));
        AccountAdmin admin = new AccountAdmin(new AccountStore(ai.store(), ai.jsonCodec()), ai.clock());
        ProviderSource source = new FakeVersionedProviderSource(Path.of(System.getProperty("exampleserver.providersDir")),
                "echo-provider.jar", "1.1.0");
        ManagementApi api = new ManagementApi(holder::listProviderIds, admin, ai.jsonCodec(), source, providersDir, holder);
        ExampleServer server = ExampleServer.start(0, api);

        try {
            Response r = get(server, "/api/providers/available");
            assertEquals(200, r.status, r.body);
            Map<String, Object> entry = availableEntry(ai.jsonCodec(), r.body, "echo");
            assertEquals(Boolean.TRUE, entry.get("installed"), r.body);
            assertEquals("1.1.0", entry.get("version"), r.body);
            assertEquals("1.0.0", entry.get("installedVersion"), r.body);
            assertEquals(Boolean.TRUE, entry.get("updateAvailable"), r.body);
        } finally {
            server.stop();
            holder.get().close();
            ai.close();
        }
    }

    @Test
    void availableReportsNoUpdateWhenSidecarIsAbsent(@TempDir Path providersDir) throws Exception {
        stageEchoJar(providersDir); // no sidecar written -- a legacy install predating this feature

        AiJava ai = AiJava.builder().storage(Storage.memory()).build();
        ServerSeeds.seedEcho(ai.store(), ai.jsonCodec(), CONFIG_FILE);
        ProviderRegistryHolder holder = new ProviderRegistryHolder(ProviderDiscovery.resolve(providersDir));
        AccountAdmin admin = new AccountAdmin(new AccountStore(ai.store(), ai.jsonCodec()), ai.clock());
        ProviderSource source = new FakeVersionedProviderSource(Path.of(System.getProperty("exampleserver.providersDir")),
                "echo-provider.jar", "1.1.0");
        ManagementApi api = new ManagementApi(holder::listProviderIds, admin, ai.jsonCodec(), source, providersDir, holder);
        ExampleServer server = ExampleServer.start(0, api);

        try {
            Response r = get(server, "/api/providers/available");
            assertEquals(200, r.status, r.body);
            Map<String, Object> entry = availableEntry(ai.jsonCodec(), r.body, "echo");
            assertEquals(Boolean.TRUE, entry.get("installed"), r.body);
            assertNull(entry.get("installedVersion"), r.body);
            assertEquals(Boolean.FALSE, entry.get("updateAvailable"), r.body);
        } finally {
            server.stop();
            holder.get().close();
            ai.close();
        }
    }

    @Test
    void availableReportsNoUpdateWhenVersionsAreEqual(@TempDir Path providersDir) throws Exception {
        Path jar = stageEchoJar(providersDir);
        InstalledVersions.write(jar, "1.1.0");

        AiJava ai = AiJava.builder().storage(Storage.memory()).build();
        ServerSeeds.seedEcho(ai.store(), ai.jsonCodec(), CONFIG_FILE);
        ProviderRegistryHolder holder = new ProviderRegistryHolder(ProviderDiscovery.resolve(providersDir));
        AccountAdmin admin = new AccountAdmin(new AccountStore(ai.store(), ai.jsonCodec()), ai.clock());
        ProviderSource source = new FakeVersionedProviderSource(Path.of(System.getProperty("exampleserver.providersDir")),
                "echo-provider.jar", "1.1.0");
        ManagementApi api = new ManagementApi(holder::listProviderIds, admin, ai.jsonCodec(), source, providersDir, holder);
        ExampleServer server = ExampleServer.start(0, api);

        try {
            Response r = get(server, "/api/providers/available");
            assertEquals(200, r.status, r.body);
            Map<String, Object> entry = availableEntry(ai.jsonCodec(), r.body, "echo");
            assertEquals("1.1.0", entry.get("installedVersion"), r.body);
            assertEquals(Boolean.FALSE, entry.get("updateAvailable"), r.body);
        } finally {
            server.stop();
            holder.get().close();
            ai.close();
        }
    }

    // -- POST /api/providers/{id}/update --

    @Test
    void updateClosesRedownloadsRewritesSidecarRefreshesRegistryAndPreservesAccounts(@TempDir Path providersDir)
            throws Exception {
        Path jar = stageEchoJar(providersDir);
        InstalledVersions.write(jar, "1.0.0");

        AiJava ai = AiJava.builder().storage(Storage.memory()).build();
        Store store = ai.store();
        JsonCodec json = ai.jsonCodec();
        ServerSeeds.seedEcho(store, json, CONFIG_FILE);
        ProviderRegistryHolder holder = new ProviderRegistryHolder(ProviderDiscovery.resolve(providersDir));
        assertTrue(holder.listProviderIds().contains("echo"));
        ProviderRegistry registryBeforeUpdate = holder.get();

        AccountStore accountStore = new AccountStore(store, json);
        accountStore.add("echo", account("acc1", "acc1@example.com"));
        AccountAdmin admin = new AccountAdmin(accountStore, ai.clock());

        ProviderSource source = new FakeVersionedProviderSource(Path.of(System.getProperty("exampleserver.providersDir")),
                "echo-provider.jar", "1.1.0");
        ManagementApi api = new ManagementApi(holder::listProviderIds, admin, json, source, providersDir, holder);
        ExampleServer server = ExampleServer.start(0, api);

        try {
            Response update = post(server, "/api/providers/echo/update");
            assertEquals(200, update.status, update.body);
            assertTrue(update.body.contains("\"updated\":true") || update.body.contains("\"updated\": true"),
                    update.body);
            assertTrue(update.body.contains("1.1.0"), update.body);
            assertTrue(update.body.contains("echo"), update.body);

            // The sidecar on disk now records the NEW version.
            assertEquals("1.1.0", InstalledVersions.read(jar));

            // A fresh registry was built (close-then-refresh), not an in-place mutation.
            assertNotSame(registryBeforeUpdate, holder.get());
            assertTrue(holder.listProviderIds().contains("echo"), holder.listProviderIds().toString());

            // Accounts live in the Store, not the jar -- untouched by the update.
            Response accounts = get(server, "/api/providers/echo/accounts");
            assertEquals(200, accounts.status, accounts.body);
            assertTrue(accounts.body.contains("acc1"), accounts.body);
        } finally {
            server.stop();
            holder.get().close();
            ai.close();
        }
    }

    @Test
    void updateResolvesOrgEntryByAssetNameWhenProviderIdDiffersFromRepoName(@TempDir Path providersDir)
            throws Exception {
        // Install the echo jar under a repo/asset name that does NOT equal the provider id -- the
        // real stub-auth (id "stub") / antigravity-auth (id "antigravity") shape. A prior version
        // resolved the update entry by id == repo name and 404'd here.
        Path jar = stageEchoJarAs(providersDir, "echo-auth-provider.jar");
        InstalledVersions.write(jar, "1.0.0");

        AiJava ai = AiJava.builder().storage(Storage.memory()).build();
        ServerSeeds.seedEcho(ai.store(), ai.jsonCodec(), CONFIG_FILE);
        ProviderRegistryHolder holder = new ProviderRegistryHolder(ProviderDiscovery.resolve(providersDir));
        assertTrue(holder.listProviderIds().contains("echo"),
                "provider id is read from the jar content, not its file name");
        AccountAdmin admin = new AccountAdmin(new AccountStore(ai.store(), ai.jsonCodec()), ai.clock());
        ProviderSource source = new MismatchedNameProviderSource(
                Path.of(System.getProperty("exampleserver.providersDir")),
                "echo-auth", "echo-auth-provider.jar", "1.1.0");
        ManagementApi api = new ManagementApi(holder::listProviderIds, admin, ai.jsonCodec(), source, providersDir, holder);
        ExampleServer server = ExampleServer.start(0, api);

        try {
            Response update = post(server, "/api/providers/echo/update");
            assertEquals(200, update.status, update.body);
            assertTrue(update.body.contains("\"updated\":true") || update.body.contains("\"updated\": true"),
                    update.body);
            assertEquals("1.1.0", InstalledVersions.read(jar), "sidecar updated despite id != repo name");
            assertTrue(holder.listProviderIds().contains("echo"), holder.listProviderIds().toString());
        } finally {
            server.stop();
            holder.get().close();
            ai.close();
        }
    }

    @Test
    void updateOnNotInstalledProviderIs404(@TempDir Path providersDir) throws Exception {
        AiJava ai = AiJava.builder().storage(Storage.memory()).build();
        ServerSeeds.seedEcho(ai.store(), ai.jsonCodec(), CONFIG_FILE);
        ProviderRegistryHolder holder = new ProviderRegistryHolder(ProviderDiscovery.resolve(providersDir));
        assertTrue(holder.listProviderIds().isEmpty(), "no jar staged -- nothing installed");
        AccountAdmin admin = new AccountAdmin(new AccountStore(ai.store(), ai.jsonCodec()), ai.clock());
        ProviderSource source = new NoEntriesProviderSource();
        ManagementApi api = new ManagementApi(holder::listProviderIds, admin, ai.jsonCodec(), source, providersDir, holder);
        ExampleServer server = ExampleServer.start(0, api);

        try {
            Response r = post(server, "/api/providers/echo/update");
            assertEquals(404, r.status, r.body);
        } finally {
            server.stop();
            if (holder.get() != null) holder.get().close();
            ai.close();
        }
    }

    @Test
    void updateWhenSourceHasNoMatchingEntryIs404(@TempDir Path providersDir) throws Exception {
        stageEchoJar(providersDir);

        AiJava ai = AiJava.builder().storage(Storage.memory()).build();
        ServerSeeds.seedEcho(ai.store(), ai.jsonCodec(), CONFIG_FILE);
        ProviderRegistryHolder holder = new ProviderRegistryHolder(ProviderDiscovery.resolve(providersDir));
        assertTrue(holder.listProviderIds().contains("echo"), "echo must be installed for this to be a real 404 case");
        AccountAdmin admin = new AccountAdmin(new AccountStore(ai.store(), ai.jsonCodec()), ai.clock());
        ProviderSource source = new NoEntriesProviderSource(); // simulates the repo vanishing upstream
        ManagementApi api = new ManagementApi(holder::listProviderIds, admin, ai.jsonCodec(), source, providersDir, holder);
        ExampleServer server = ExampleServer.start(0, api);

        try {
            Response r = post(server, "/api/providers/echo/update");
            assertEquals(404, r.status, r.body);
            assertTrue(r.body.contains("error"), r.body);
            assertFalse(holder.listProviderIds().isEmpty(), "a failed update must not have torn down the registry");
        } finally {
            server.stop();
            holder.get().close();
            ai.close();
        }
    }

    /** Parses the {@code /api/providers/available} JSON array and returns the entry whose
     *  {@code name} matches, mirroring the sibling install/org-scan integration tests. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> availableEntry(JsonCodec json, String rawAvailableJson, String name) {
        List<Object> entries = (List<Object>) json.parse(rawAvailableJson);
        for (Object o : entries) {
            Map<String, Object> entry = (Map<String, Object>) o;
            if (name.equals(entry.get("name"))) {
                return entry;
            }
        }
        throw new AssertionError("no /api/providers/available entry named \"" + name + "\": " + rawAvailableJson);
    }

    // -- tiny loopback HTTP client (test-only; mirrors the sibling integration tests' helper) --

    private static Response get(ExampleServer server, String path) throws IOException {
        return read(open(server, path, "GET"));
    }

    private static Response post(ExampleServer server, String path) throws IOException {
        HttpURLConnection c = open(server, path, "POST");
        c.setDoOutput(true);
        try (OutputStream os = c.getOutputStream()) {
            os.write(new byte[0]);
        }
        return read(c);
    }

    private static HttpURLConnection open(ExampleServer server, String path, String method) throws IOException {
        URL url = new URL("http://127.0.0.1:" + server.port() + path);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod(method);
        return c;
    }

    private static Response read(HttpURLConnection c) throws IOException {
        int status = c.getResponseCode();
        InputStream is = status < 400 ? c.getInputStream() : c.getErrorStream();
        String text = "";
        if (is != null) {
            try (Scanner s = new Scanner(is, "UTF-8").useDelimiter("\\A")) {
                text = s.hasNext() ? s.next() : "";
            }
        }
        return new Response(status, text);
    }

    private static final class Response {
        final int status;
        final String body;
        Response(int status, String body) { this.status = status; this.body = body; }
    }
}
