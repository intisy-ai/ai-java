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
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the on-demand install API end to end with a {@link FakeProviderSource} (no network):
 * boot with an EMPTY providers dir (0 providers loaded), confirm {@code /api/providers/available}
 * reports the fake entry as not installed, install it, then confirm the live registry refreshed
 * without a restart -- {@code /api/providers} now lists it and a DIRECT {@code POST
 * /api/providers/echo/messages} call reaches it (console chat never goes through a router).
 */
class ProviderInstallIntegrationTest {

    private static final String CONFIG_FILE = "provider-install-routing.json";

    private AiJava ai;
    private ExampleServer server;
    private ProviderRegistryHolder holder;
    private Path providersDir;
    private JsonCodec json;

    @BeforeEach
    void setUp(@TempDir Path providersDir) {
        this.providersDir = providersDir;
        ai = AiJava.builder().storage(Storage.memory()).build();
        Store store = ai.store();
        json = ai.jsonCodec();
        ServerSeeds.seedEcho(store, json, CONFIG_FILE);

        holder = new ProviderRegistryHolder(ProviderDiscovery.resolve(providersDir));
        assertTrue(holder.listProviderIds().isEmpty(), "must start with zero providers loaded");

        AccountStore accountStore = new AccountStore(store, json);
        AccountAdmin admin = new AccountAdmin(accountStore, ai.clock());

        String stagedDir = System.getProperty("exampleserver.providersDir");
        ProviderSource fakeSource = new FakeProviderSource(Path.of(stagedDir));
        MessagesAdmin messages = new MessagesAdmin(store, json, holder, ai.logger());

        ManagementApi api = new ManagementApi(holder::listProviderIds, admin, json,
                fakeSource, providersDir, holder, null, null, null, null, null, null, null, null, messages);

        server = ExampleServer.start(0, api); // ephemeral port
    }

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) server.stop();
        if (holder != null && holder.get() != null) holder.get().close();
        if (ai != null) ai.close();
    }

    @Test
    void availableListsFakeEntryAsNotInstalled() throws IOException {
        Response r = get("/api/providers/available");
        assertEquals(200, r.status);
        assertTrue(r.body.contains("echo-demo"), r.body);
        assertTrue(r.body.contains("\"installed\":false") || r.body.contains("\"installed\": false"), r.body);
    }

    @Test
    void providersListLacksEchoBeforeInstall() throws IOException {
        Response r = get("/api/providers");
        assertEquals(200, r.status);
        assertFalse(r.body.contains("\"echo\""), r.body);
    }

    @Test
    void installUnknownNameIs404() throws IOException {
        Response r = post("/api/providers/install", "{\"name\":\"does-not-exist\"}");
        assertEquals(404, r.status);
        assertTrue(r.body.contains("unknown provider"), r.body);
    }

    @Test
    void installThenRefreshesLiveRegistryAndRoutes() throws IOException {
        Response install = post("/api/providers/install", "{\"name\":\"echo-demo\"}");
        assertEquals(200, install.status, install.body);
        assertTrue(install.body.contains("\"installed\":true") || install.body.contains("\"installed\": true"),
                install.body);
        assertTrue(install.body.contains("echo"), install.body);

        Response afterList = get("/api/providers");
        assertEquals(200, afterList.status);
        assertTrue(afterList.body.contains("\"echo\""), afterList.body);

        Response afterAvailable = get("/api/providers/available");
        assertEquals(200, afterAvailable.status);
        assertTrue(afterAvailable.body.contains("\"installed\":true") || afterAvailable.body.contains("\"installed\": true"),
                afterAvailable.body);

        // Console chat is a DIRECT provider call now, never a router match -- POST straight to the
        // just-installed provider's own /api/providers/{id}/messages.
        String body = "{\"model\":\"claude-haiku-4\",\"messages\":[]}";
        Response chat = post("/api/providers/echo/messages", body);
        assertEquals(200, chat.status, chat.body);
        assertTrue(chat.body.contains("Echo provider handled your request"), chat.body);
    }

    @Test
    void uninstallDeletesJarAndDropsProviderFromLiveRegistry() throws IOException {
        Response install = post("/api/providers/install", "{\"name\":\"echo-demo\"}");
        assertEquals(200, install.status, install.body);
        assertTrue(holder.listProviderIds().contains("echo"), holder.listProviderIds().toString());

        Path jar = providersDir.resolve("echo-provider.jar");
        assertTrue(Files.exists(jar), "echo-provider.jar should be staged after install");

        Response uninstall = delete("/api/providers/echo");
        assertEquals(200, uninstall.status, uninstall.body);
        assertTrue(uninstall.body.contains("\"uninstalled\":true") || uninstall.body.contains("\"uninstalled\": true"),
                uninstall.body);

        // Windows-safe delete: the registry's URLClassLoader must have been closed BEFORE this
        // delete, or the still-open jar handle would make Files.deleteIfExists fail silently here.
        assertFalse(Files.exists(jar), "jar should be deleted from disk after uninstall");
        assertFalse(holder.listProviderIds().contains("echo"), holder.listProviderIds().toString());

        Response afterList = get("/api/providers");
        assertEquals(200, afterList.status);
        assertFalse(afterList.body.contains("\"echo\""), afterList.body);
    }

    @Test
    void uninstallUnknownProviderIs404() throws IOException {
        Response r = delete("/api/providers/does-not-exist");
        assertEquals(404, r.status);
    }

    @Test
    void availableMarksEntryInstalledWhenNameMatchesInstalledIdEvenWithoutItsOwnAssetFile() throws IOException {
        Response install = post("/api/providers/install", "{\"name\":\"echo-demo\"}");
        assertEquals(200, install.status, install.body);

        // The "echo" entry's own asset was deliberately never downloaded -- only "echo-provider.jar"
        // (the "echo-demo" entry's asset) exists on disk. It matches an installed provider id anyway.
        assertFalse(Files.exists(providersDir.resolve("echo-renamed-asset.jar")));

        Response available = get("/api/providers/available");
        assertEquals(200, available.status);
        assertEquals(Boolean.TRUE, availableEntry(available.body, "echo").get("installed"), available.body);
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

    /** Simulates a real download with no network: copies the already-staged example-provider jar
     *  ({@code exampleserver.providersDir}, populated by the Gradle test task) into the target dir. */
    private static final class FakeProviderSource implements ProviderSource {
        private final Path stagedJarDir;

        FakeProviderSource(Path stagedJarDir) {
            this.stagedJarDir = stagedJarDir;
        }

        @Override
        public List<Entry> list() {
            // "echo" mirrors an org asset that was renamed to match its installed provider id
            // (DECISION-FLAG dedupe fix): its own assetName is never downloaded/present on disk,
            // so it only reports installed:true via the name-matches-an-installed-id branch.
            return Arrays.asList(
                    new Entry("echo-demo", "echo-provider.jar", ""),
                    new Entry("echo", "echo-renamed-asset.jar", ""));
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
            Path src = findStagedJar();
            Path target = dir.resolve(entry.assetName);
            Files.copy(src, target);
            return target;
        }

        private Path findStagedJar() throws IOException {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(stagedJarDir, "*.jar")) {
                for (Path p : stream) {
                    return p;
                }
            }
            throw new IOException("no staged provider jar found in " + stagedJarDir);
        }
    }

    // -- tiny loopback HTTP client (test-only; newer JDK APIs allowed in tests) --

    private Response get(String path) throws IOException {
        HttpURLConnection c = open(path, "GET");
        return read(c);
    }

    private Response delete(String path) throws IOException {
        HttpURLConnection c = open(path, "DELETE");
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
        return new Response(status, text);
    }

    private static final class Response {
        final int status;
        final String body;
        Response(int status, String body) { this.status = status; this.body = body; }
    }
}
