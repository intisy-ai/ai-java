package io.github.intisy.ai.exampleserver;

import io.github.intisy.ai.exampleserver.admin.AccountAdmin;
import io.github.intisy.ai.exampleserver.admin.ProxyAdmin;
import io.github.intisy.ai.exampleserver.admin.QuotaAdmin;
import io.github.intisy.ai.exampleserver.admin.RoutingAdmin;
import io.github.intisy.ai.exampleserver.api.ManagementApi;
import io.github.intisy.ai.exampleserver.discovery.ProviderDiscovery;
import io.github.intisy.ai.exampleserver.discovery.ProviderRegistryHolder;
import io.github.intisy.ai.exampleserver.discovery.ProxyDiscovery;
import io.github.intisy.ai.exampleserver.discovery.ProxyRegistryHolder;
import io.github.intisy.ai.exampleserver.discovery.ProxySource;
import io.github.intisy.ai.jvm.AiJava;
import io.github.intisy.ai.jvm.Storage;
import io.github.intisy.ai.shared.routing.ProxyPlugin;
import io.github.intisy.ai.shared.routing.RoutingProfile;
import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.Store;
import io.github.intisy.ai.shared.store.AccountStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Scanner;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Boots {@link ExampleServer} with the proxy-management surface wired in (the full 11-arg
 * {@link ManagementApi} constructor, with {@link ProxyAdmin} backed by a real {@link ProxyManager})
 * and drives {@code /api/proxies*} over loopback, mirroring {@link QuotaApiIntegrationTest}'s
 * harness style. A file-backed store is used (not memory) so {@code proxies.json} round-trips the
 * way it would in a real boot. The test actually starts a proxy on an ephemeral port (bound via
 * {@code {"port":0}}, never a fixed port, so the test is hermetic) and confirms it serves
 * {@code /healthz} for real before stopping it again. The proxy under test is an INSTALLED
 * {@link ProxyPlugin} fixture (not a hardcoded "claude-code" app) — {@link #stageProxyJar} packages
 * it into a real jar the same way {@link ProxyManagerTest}'s fixtures are staged.
 */
class ProxyApiIntegrationTest {

    private static final String CONFIG_FILE = "proxy-api-routing.json";
    private static final String PROXY_ID = "routing-proxy";
    private static final String INSTALL_NAME = "installable-proxy";
    private static final String INSTALL_ASSET = "installable-proxy.jar";
    private static final Pattern PORT_PATTERN = Pattern.compile("\"port\"\\s*:\\s*(\\d+)");

    private AiJava ai;
    private ExampleServer server;
    private ProviderRegistryHolder holder;
    private ProxyRegistryHolder proxyHolder;
    private ProxyManager proxyManager;
    private Path proxiesDir;

    @BeforeEach
    void setUp(@TempDir Path providersDir, @TempDir Path configDir) throws IOException {
        // proxiesDir is NOT a JUnit @TempDir: the install/uninstall tests trigger a SECOND
        // ProxyRegistryHolder#refresh, whose documented tradeoff leaks the FIRST registry's
        // URLClassLoader (still holding fixture-proxy.jar open on Windows) -- JUnit's @TempDir
        // cleanup treats that as a hard failure, so this dir is cleaned up best-effort instead
        // (see tearDown).
        proxiesDir = Files.createTempDirectory("proxy-api-test-proxies");
        stageProviderJar(providersDir);
        stageProxyJar(proxiesDir);

        ai = AiJava.builder().storage(Storage.file(configDir)).build();
        Store store = ai.store();
        JsonCodec json = ai.jsonCodec();
        ServerSeeds.seedEcho(store, json, CONFIG_FILE);

        holder = new ProviderRegistryHolder(ProviderDiscovery.resolve(providersDir));
        assertTrue(holder.listProviderIds().contains("echo"), holder.listProviderIds().toString());
        proxyHolder = new ProxyRegistryHolder(ProxyDiscovery.resolve(proxiesDir));
        assertTrue(proxyHolder.listProxyIds().contains(PROXY_ID), proxyHolder.listProxyIds().toString());

        AccountStore accountStore = new AccountStore(store, json);
        AccountAdmin admin = new AccountAdmin(accountStore, ai.clock());

        RoutingAdmin routing = new RoutingAdmin(store, json, holder, ai.logger());
        QuotaAdmin quota = new QuotaAdmin(store, json, holder, ai.logger());
        proxyManager = new ProxyManager(ai, holder, proxyHolder, store, json, ai.logger());
        ProxyAdmin proxyAdmin = new ProxyAdmin(proxyManager);
        ProxySource fakeProxySource = new FakeProxySource();
        ManagementApi api = new ManagementApi(holder::listProviderIds, admin, json, null, null, holder,
                routing, quota, null, null, proxyAdmin, fakeProxySource, proxyHolder, proxiesDir);

        server = ExampleServer.start(0, api); // ephemeral port
    }

    @AfterEach
    void tearDown() throws IOException {
        if (proxyManager != null) proxyManager.stopAll(); // release any ephemeral proxy port
        if (server != null) server.stop();
        if (holder != null && holder.get() != null) holder.get().close();
        if (proxyHolder != null && proxyHolder.get() != null) proxyHolder.get().close();
        if (ai != null) ai.close();
        deleteBestEffort(proxiesDir);
    }

    /** Best-effort recursive delete: a leaked {@link java.net.URLClassLoader} from an earlier
     *  {@link ProxyRegistryHolder#refresh} may still hold a jar open on Windows (an accepted
     *  tradeoff, see {@link ProxyRegistryHolder#refresh}'s javadoc) -- leftover files are simply
     *  abandoned to the OS temp-dir sweep rather than failing the test. */
    private static void deleteBestEffort(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (java.util.stream.Stream<Path> stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // still locked by a leaked classloader -- leave it for the OS to reclaim
                }
            });
        } catch (IOException ignored) {
            // walk itself failed; nothing more we can do here
        }
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

    /**
     * Packages the already-compiled {@link RoutingFixtureProxyPlugin} {@code .class} plus a real
     * {@code META-INF/services} registration into an actual jar in {@code proxiesDir} — mirrors
     * {@code ProxyManagerTest#stageProxyJar}, kept local here since this test drives the proxy
     * through the HTTP API rather than {@link ProxyManager} directly.
     */
    private static void stageProxyJar(Path proxiesDir) throws IOException {
        Files.write(proxiesDir.resolve("fixture-proxy.jar"), buildProxyJarBytes(RoutingFixtureProxyPlugin.class));
    }

    /** Packages a compiled {@link ProxyPlugin} fixture class plus a real
     *  {@code META-INF/services} registration into an in-memory jar -- shared by the initial
     *  {@link #stageProxyJar} staging and {@link FakeProxySource#download}, which simulates a real
     *  install download with no network. */
    private static byte[] buildProxyJarBytes(Class<? extends ProxyPlugin> pluginClass) throws IOException {
        String className = pluginClass.getName();
        String classResourcePath = className.replace('.', '/') + ".class";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(baos)) {
            try (InputStream in = ProxyApiIntegrationTest.class.getClassLoader().getResourceAsStream(classResourcePath)) {
                if (in == null) throw new IllegalStateException("missing compiled class on test classpath: " + classResourcePath);
                jar.putNextEntry(new JarEntry(classResourcePath));
                jar.write(in.readAllBytes());
                jar.closeEntry();
            }
            jar.putNextEntry(new JarEntry("META-INF/services/" + ProxyPlugin.class.getName()));
            jar.write(className.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return baos.toByteArray();
    }

    /** Simulates a real proxy download with no network: {@link #find}/{@link #list} report a
     *  single installable entry ({@link #INSTALL_NAME}), and {@link #download} writes a real jar
     *  for {@link InstallableFixtureProxyPlugin} -- mirrors {@code ProviderInstallIntegrationTest}'s
     *  {@code FakeProviderSource}. {@link #find} is implemented directly (not by delegating through
     *  {@link #list}) so the install route's find()-first lookup is genuinely exercised. */
    private static final class FakeProxySource implements ProxySource {
        @Override
        public List<Entry> list() {
            return Collections.singletonList(new Entry(INSTALL_NAME, INSTALL_ASSET, ""));
        }

        @Override
        public Entry find(String name) {
            return INSTALL_NAME.equals(name) ? new Entry(INSTALL_NAME, INSTALL_ASSET, "") : null;
        }

        @Override
        public Path download(Entry entry, Path dir) throws IOException {
            Path target = dir.resolve(entry.assetName);
            Files.write(target, buildProxyJarBytes(InstallableFixtureProxyPlugin.class));
            return target;
        }
    }

    /** Test-only proxy plugin installed on demand via {@code POST /api/proxies/install}. */
    public static final class InstallableFixtureProxyPlugin implements ProxyPlugin {
        @Override public String id() { return INSTALL_NAME; }
        @Override public String displayName() { return "Installable Proxy"; }
        @Override public RoutingProfile profile() { return ServerProfile.echoTiers(CONFIG_FILE); }
    }

    @Test
    void listReturnsInstalledProxy() throws IOException {
        Response r = get("/api/proxies");
        assertEquals(200, r.status, r.body);
        assertTrue(r.body.contains(PROXY_ID), r.body);
    }

    @Test
    void putPortStartThenStop() throws IOException {
        assertEquals(200, put("/api/proxies/" + PROXY_ID, "{\"port\":0}").status); // 0 -> ephemeral
        Response started = post("/api/proxies/" + PROXY_ID + "/start");
        assertEquals(200, started.status, started.body);
        assertTrue(started.body.contains("\"running\":true"), started.body);
        int port = extractPort(started.body);
        assertEquals(200, healthz(port));            // the started proxy actually serves
        assertEquals(200, post("/api/proxies/" + PROXY_ID + "/stop").status);
    }

    @Test
    void unknownIdIs400() throws IOException {
        assertEquals(400, post("/api/proxies/nope/start").status);
    }

    @Test
    void availableListsFakeEntryAsNotInstalled() throws IOException {
        Response r = get("/api/proxies/available");
        assertEquals(200, r.status, r.body);
        assertTrue(r.body.contains(INSTALL_NAME), r.body);
        assertTrue(r.body.contains("\"installed\":false") || r.body.contains("\"installed\": false"), r.body);
    }

    @Test
    void installKnownNameDownloadsAndRefreshesLiveRegistry() throws IOException {
        Response install = post("/api/proxies/install", "{\"name\":\"" + INSTALL_NAME + "\"}");
        assertEquals(200, install.status, install.body);
        assertTrue(install.body.contains("\"installed\":true") || install.body.contains("\"installed\": true"),
                install.body);
        assertTrue(proxyHolder.listProxyIds().contains(INSTALL_NAME), proxyHolder.listProxyIds().toString());
    }

    @Test
    void installUnknownNameIs404() throws IOException {
        Response r = post("/api/proxies/install", "{\"name\":\"nope\"}");
        assertEquals(404, r.status);
        assertTrue(r.body.contains("unknown proxy"), r.body);
    }

    @Test
    void uninstallInstalledProxyRemovesItAndUnknownIs404() throws IOException {
        assertEquals(200, post("/api/proxies/install", "{\"name\":\"" + INSTALL_NAME + "\"}").status);
        assertTrue(proxyHolder.listProxyIds().contains(INSTALL_NAME), proxyHolder.listProxyIds().toString());

        Response uninstall = delete("/api/proxies/" + INSTALL_NAME);
        assertEquals(200, uninstall.status, uninstall.body);
        assertTrue(uninstall.body.contains("\"uninstalled\":true") || uninstall.body.contains("\"uninstalled\": true"),
                uninstall.body);
        assertFalse(proxyHolder.listProxyIds().contains(INSTALL_NAME), proxyHolder.listProxyIds().toString());

        assertEquals(404, delete("/api/proxies/does-not-exist").status);
    }

    @Test
    void modelMapResolvesInstalledProxyProfileAndRejectsUnknownApp() throws IOException {
        Response installed = get("/api/routing/model-map?app=" + PROXY_ID);
        assertEquals(200, installed.status, installed.body);
        assertTrue(installed.body.contains("\"tiers\""), installed.body);

        assertEquals(400, get("/api/routing/model-map?app=nope").status);
    }

    /** Test-only proxy plugin whose profile matches this test's own echo-tiers routing profile. */
    public static final class RoutingFixtureProxyPlugin implements ProxyPlugin {
        @Override public String id() { return PROXY_ID; }
        @Override public String displayName() { return "Routing Proxy"; }
        @Override public RoutingProfile profile() { return ServerProfile.echoTiers(CONFIG_FILE); }
    }

    private static int extractPort(String body) {
        Matcher m = PORT_PATTERN.matcher(body);
        assertTrue(m.find(), body);
        return Integer.parseInt(m.group(1));
    }

    private static int healthz(int port) throws IOException {
        URL url = new URL("http://127.0.0.1:" + port + "/healthz");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("GET");
        return c.getResponseCode();
    }

    // -- tiny loopback HTTP client (test-only; mirrors QuotaApiIntegrationTest's helper) --

    private Response get(String path) throws IOException {
        return read(open(path, "GET"));
    }

    private Response put(String path, String body) throws IOException {
        HttpURLConnection c = open(path, "PUT");
        c.setDoOutput(true);
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return read(c);
    }

    private Response post(String path) throws IOException {
        HttpURLConnection c = open(path, "POST");
        c.setDoOutput(true);
        try (OutputStream os = c.getOutputStream()) {
            os.write(new byte[0]);
        }
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

    private Response delete(String path) throws IOException {
        return read(open(path, "DELETE"));
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
