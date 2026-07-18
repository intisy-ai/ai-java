package io.github.intisy.ai.exampleserver;

import io.github.intisy.ai.exampleserver.admin.AccountAdmin;
import io.github.intisy.ai.exampleserver.admin.ProxyAdmin;
import io.github.intisy.ai.exampleserver.api.ManagementApi;
import io.github.intisy.ai.exampleserver.discovery.ProviderDiscovery;
import io.github.intisy.ai.exampleserver.discovery.ProviderRegistryHolder;
import io.github.intisy.ai.exampleserver.discovery.ProviderSource;
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
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.List;
import java.util.Scanner;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end proof that the three reported org-scan bugs are fixed: an empty/failed org scan
 * (a {@link ProviderSource}/{@link ProxySource} whose {@code list()} always comes back empty --
 * exactly how {@code GithubOrgScan#scan} degrades on any network/auth/parse failure) must NEVER
 * (1) wipe the installed-providers/-proxies render, nor (2) block a targeted install via
 * {@code find()}. Both {@link #providerSource} and {@link #proxySource} below return an empty
 * {@code list()} for the whole test, so {@code /api/providers/available} and
 * {@code /api/proxies/available} are themselves asserted empty as the premise -- this isn't a
 * best-effort stand-in for a failed scan, it genuinely reproduces one at the API surface.
 *
 * <p>Reuses the jar-staging techniques of the sibling integration tests: {@link
 * ProviderInstallIntegrationTest} for the provider fixture (the real staged {@code
 * exampleserver.providersDir} jar, re-copied under a fixed asset name so the pre-seeded and
 * install-via-find jars are the SAME provider id and never duplicate-register), and {@link
 * ProxyApiIntegrationTest} for the in-memory {@link ProxyPlugin} fixture jars (a routing-only
 * pre-installed proxy, plus a second, distinct id installed via {@code find()}).
 */
class OrgScanResilienceIntegrationTest {

    private static final String CONFIG_FILE = "org-scan-resilience-routing.json";

    private static final String PROVIDER_ASSET_NAME = "echo-provider.jar";
    private static final String INSTALL_PROVIDER_NAME = "echo-demo";

    private static final String PROXY_ID = "resilience-proxy";
    private static final String PROXY_ASSET_NAME = "resilience-proxy.jar";
    private static final String INSTALL_PROXY_NAME = "resilience-installable-proxy";
    private static final String INSTALL_PROXY_ASSET = "resilience-installable-proxy.jar";

    private AiJava ai;
    private ExampleServer server;
    private ProviderRegistryHolder holder;
    private ProxyRegistryHolder proxyHolder;
    private ProxyManager proxyManager;
    private Path providersDir;
    private Path proxiesDir;

    @BeforeEach
    void setUp() throws IOException {
        // Neither dir is a JUnit @TempDir: the install tests trigger a SECOND registry refresh,
        // whose documented tradeoff leaks the FIRST registry's URLClassLoader (still holding a jar
        // open on Windows) -- @TempDir cleanup treats that still-open handle as a hard failure. See
        // ProxyApiIntegrationTest for the same rationale. Cleaned up best-effort in tearDown instead.
        providersDir = Files.createTempDirectory("org-scan-resilience-providers");
        proxiesDir = Files.createTempDirectory("org-scan-resilience-proxies");

        stageProviderJar(providersDir);
        stageProxyJar(proxiesDir);

        ai = AiJava.builder().storage(Storage.memory()).build();
        Store store = ai.store();
        JsonCodec json = ai.jsonCodec();
        ServerSeeds.seedEcho(store, json, CONFIG_FILE);

        holder = new ProviderRegistryHolder(ProviderDiscovery.resolve(providersDir));
        assertTrue(holder.listProviderIds().contains("echo"), holder.listProviderIds().toString());
        proxyHolder = new ProxyRegistryHolder(ProxyDiscovery.resolve(proxiesDir));
        assertTrue(proxyHolder.listProxyIds().contains(PROXY_ID), proxyHolder.listProxyIds().toString());

        AccountStore accountStore = new AccountStore(store, json);
        AccountAdmin admin = new AccountAdmin(accountStore, ai.clock());

        proxyManager = new ProxyManager(ai, holder, proxyHolder, store, json, ai.logger());
        ProxyAdmin proxyAdmin = new ProxyAdmin(proxyManager);

        ProviderSource providerSource = new EmptyScanProviderSource(providersDir);
        ProxySource proxySource = new EmptyScanProxySource();

        ManagementApi api = new ManagementApi(holder::listProviderIds, admin, json,
                providerSource, providersDir, holder,
                null, null, null, null,
                proxyAdmin, proxySource, proxyHolder, proxiesDir);

        server = ExampleServer.start(0, api); // ephemeral port
    }

    @AfterEach
    void tearDown() throws IOException {
        if (proxyManager != null) proxyManager.stopAll();
        if (server != null) server.stop();
        if (holder != null && holder.get() != null) holder.get().close();
        if (proxyHolder != null && proxyHolder.get() != null) proxyHolder.get().close();
        if (ai != null) ai.close();
        deleteBestEffort(providersDir);
        deleteBestEffort(proxiesDir);
    }

    /** Best-effort recursive delete -- see {@link ProxyApiIntegrationTest#deleteBestEffort} for
     *  why {@code proxiesDir} can't rely on JUnit's own {@code @TempDir} cleanup here. */
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

    /** Copies the real, already-built provider jar (staged by the Gradle test task at {@code
     *  exampleserver.providersDir}) into {@code targetDir} under a FIXED name, so the same jar can
     *  later be "re-installed" via {@code find()} onto that exact asset name without registering a
     *  second, duplicate "echo" provider id. */
    private static void stageProviderJar(Path targetDir) throws IOException {
        String staged = System.getProperty("exampleserver.providersDir");
        assertNotNull(staged, "exampleserver.providersDir must be set by the Gradle test task");
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(Path.of(staged), "*.jar")) {
            for (Path jar : stream) {
                Files.copy(jar, targetDir.resolve(PROVIDER_ASSET_NAME));
                return;
            }
        }
        fail("no staged provider jar found in " + staged);
    }

    private static void stageProxyJar(Path proxiesDir) throws IOException {
        Files.write(proxiesDir.resolve(PROXY_ASSET_NAME), buildProxyJarBytes(RoutingFixtureProxyPlugin.class));
    }

    /** Packages a compiled {@link ProxyPlugin} fixture class plus a real
     *  {@code META-INF/services} registration into an in-memory jar -- mirrors {@code
     *  ProxyApiIntegrationTest#buildProxyJarBytes}. */
    private static byte[] buildProxyJarBytes(Class<? extends ProxyPlugin> pluginClass) throws IOException {
        String className = pluginClass.getName();
        String classResourcePath = className.replace('.', '/') + ".class";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(baos)) {
            try (InputStream in = OrgScanResilienceIntegrationTest.class.getClassLoader()
                    .getResourceAsStream(classResourcePath)) {
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

    /** Test-only proxy plugin pre-installed in {@code proxiesDir} at startup. */
    public static final class RoutingFixtureProxyPlugin implements ProxyPlugin {
        @Override public String id() { return PROXY_ID; }
        @Override public String displayName() { return "Resilience Routing Proxy"; }
        @Override public RoutingProfile profile() { return ServerProfile.echoTiers(CONFIG_FILE); }
    }

    /** Test-only proxy plugin installed on demand via {@code POST /api/proxies/install}, while
     *  {@link EmptyScanProxySource#list()} reports zero available proxies. */
    public static final class InstallableFixtureProxyPlugin implements ProxyPlugin {
        @Override public String id() { return INSTALL_PROXY_NAME; }
        @Override public String displayName() { return "Resilience Installable Proxy"; }
        @Override public RoutingProfile profile() { return ServerProfile.echoTiers(CONFIG_FILE); }
    }

    /** Simulates a failed/rate-limited org scan for providers: {@link #list()} ALWAYS reports zero
     *  entries (exactly how {@code GithubOrgScan#scan} degrades on any network/auth/parse failure),
     *  but {@link #find(String)} still resolves the one known name -- mirroring {@code
     *  GithubOrgScan#scanRepo}'s targeted, single-repo lookup that never depends on the cached org
     *  scan. {@link #download} re-copies the SAME staged jar onto the SAME already-installed asset
     *  name so re-installing "echo-demo" never registers a second, duplicate "echo" provider id. */
    private static final class EmptyScanProviderSource implements ProviderSource {
        private final Path providersDir;

        EmptyScanProviderSource(Path providersDir) {
            this.providersDir = providersDir;
        }

        @Override
        public List<Entry> list() {
            return Collections.emptyList();
        }

        @Override
        public Entry find(String name) {
            return INSTALL_PROVIDER_NAME.equals(name)
                    ? new Entry(INSTALL_PROVIDER_NAME, PROVIDER_ASSET_NAME, "", "1.0.0")
                    : null;
        }

        @Override
        public Path download(Entry entry, Path dir) throws IOException {
            Path target = dir.resolve(entry.assetName);
            Files.copy(providersDir.resolve(PROVIDER_ASSET_NAME), target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        }
    }

    /** Proxy-side mirror of {@link EmptyScanProviderSource}: {@link #list()} always empty, {@link
     *  #find(String)} still resolves {@link #INSTALL_PROXY_NAME} and downloads a real, distinct
     *  {@link InstallableFixtureProxyPlugin} jar. */
    private static final class EmptyScanProxySource implements ProxySource {
        @Override
        public List<Entry> list() {
            return Collections.emptyList();
        }

        @Override
        public Entry find(String name) {
            return INSTALL_PROXY_NAME.equals(name)
                    ? new Entry(INSTALL_PROXY_NAME, INSTALL_PROXY_ASSET, "")
                    : null;
        }

        @Override
        public Path download(Entry entry, Path dir) throws IOException {
            Path target = dir.resolve(entry.assetName);
            Files.write(target, buildProxyJarBytes(InstallableFixtureProxyPlugin.class));
            return target;
        }
    }

    // -- the premise: both /available endpoints genuinely report an empty/failed scan --

    @Test
    void bothAvailableEndpointsReportAnEmptyScanAsThePremiseOfThisTest() throws IOException {
        Response providersAvailable = get("/api/providers/available");
        assertEquals(200, providersAvailable.status, providersAvailable.body);
        assertEquals("[]", providersAvailable.body.trim(), providersAvailable.body);

        Response proxiesAvailable = get("/api/proxies/available");
        assertEquals(200, proxiesAvailable.status, proxiesAvailable.body);
        assertEquals("[]", proxiesAvailable.body.trim(), proxiesAvailable.body);
    }

    // -- bug 1: an empty/failed scan must never wipe the installed-providers render --

    @Test
    void installedProvidersRenderIsIndependentOfAFailedOrEmptyOrgScan() throws IOException {
        Response r = get("/api/providers");
        assertEquals(200, r.status, r.body);
        assertTrue(r.body.contains("\"echo\""), r.body);
    }

    // -- bug 2: install must still succeed via find() even when list() is empty (providers) --

    @Test
    void providerInstallStillSucceedsViaFindEvenWhenListIsEmpty() throws IOException {
        Response install = post("/api/providers/install", "{\"name\":\"" + INSTALL_PROVIDER_NAME + "\"}");
        assertEquals(200, install.status, install.body);
        assertTrue(install.body.contains("\"installed\":true") || install.body.contains("\"installed\": true"),
                install.body);
        assertTrue(holder.listProviderIds().contains("echo"), holder.listProviderIds().toString());
    }

    // -- bug 1, proxy side: an empty/failed scan must never wipe the installed-proxies render --

    @Test
    void installedProxiesRenderIsIndependentOfAFailedOrEmptyAvailableScan() throws IOException {
        Response r = get("/api/proxies");
        assertEquals(200, r.status, r.body);
        assertTrue(r.body.contains(PROXY_ID), r.body);
    }

    // -- bug 2, proxy side: install must still succeed via find() even when list() is empty --

    @Test
    void proxyInstallStillSucceedsViaFindEvenWhenListIsEmpty() throws IOException {
        Response install = post("/api/proxies/install", "{\"name\":\"" + INSTALL_PROXY_NAME + "\"}");
        assertEquals(200, install.status, install.body);
        assertTrue(install.body.contains("\"installed\":true") || install.body.contains("\"installed\": true"),
                install.body);
        assertTrue(proxyHolder.listProxyIds().contains(INSTALL_PROXY_NAME), proxyHolder.listProxyIds().toString());
    }

    // -- tiny loopback HTTP client (test-only; mirrors the sibling integration tests' helper) --

    private Response get(String path) throws IOException {
        return read(open(path, "GET"));
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
