package io.github.intisy.ai.exampleserver;

import io.github.intisy.ai.exampleserver.discovery.ProviderDiscovery;
import io.github.intisy.ai.exampleserver.discovery.ProviderRegistryHolder;
import io.github.intisy.ai.exampleserver.discovery.ProxyDiscovery;
import io.github.intisy.ai.exampleserver.discovery.ProxyRegistryHolder;
import io.github.intisy.ai.jvm.AiJava;
import io.github.intisy.ai.jvm.Storage;
import io.github.intisy.ai.shared.routing.ProxyPlugin;
import io.github.intisy.ai.shared.routing.RoutingProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link ProxyManager} is generic: it runs one proxy per proxy plugin INSTALLED (discovered
 * via {@link ProxyRegistryHolder}), not a hardcoded app list. {@link #stageProxyJar} packages two
 * already-compiled {@link ProxyPlugin} fixtures (a real staged echo-tiers profile, and a
 * null-profile passthrough) into an actual jar with a {@code META-INF/services} registration,
 * mirroring how {@code ProviderRegistryTest#writeStubProviderJar} proves {@code ProviderRegistry}
 * discovery, same shape, proxy-side.
 */
class ProxyManagerTest {

    private static final String ROUTING_ID = "routing-proxy";
    private static final String NO_ROUTING_ID = "no-routing-proxy";

    private AiJava ai;
    private ProviderRegistryHolder providerHolder;
    private ProxyRegistryHolder proxyHolder;
    private ProxyManager mgr;
    private ProxyManager mgr2;

    @BeforeEach
    void setUp(@TempDir Path providersDir, @TempDir Path proxiesDir, @TempDir Path configDir) throws IOException {
        stageProviderJar(providersDir);
        stageProxyJar(proxiesDir);
        ai = AiJava.builder().storage(Storage.file(configDir)).build();
        providerHolder = new ProviderRegistryHolder(ProviderDiscovery.resolve(providersDir));
        proxyHolder = new ProxyRegistryHolder(ProxyDiscovery.resolve(proxiesDir));
        mgr = new ProxyManager(ai, providerHolder, proxyHolder, ai.store(), ai.jsonCodec(), ai.logger());
    }

    @AfterEach
    void tearDown() throws IOException {
        if (mgr != null) mgr.stopAll();
        if (mgr2 != null) mgr2.stopAll();
        if (providerHolder != null && providerHolder.get() != null) providerHolder.get().close();
        if (proxyHolder != null && proxyHolder.get() != null) proxyHolder.get().close();
        if (ai != null) ai.close();
    }

    @Test
    void listReturnsOneStatusPerInstalledProxy_notAHardcodedAppList() {
        assertEquals(2, mgr.list().size());
        assertNotNull(statusOf(ROUTING_ID));
        assertNotNull(statusOf(NO_ROUTING_ID));
    }

    @Test
    void emptyProxyHolderYieldsNoProxies(@TempDir Path emptyProxiesDir) {
        ProxyRegistryHolder empty = new ProxyRegistryHolder(ProxyDiscovery.resolve(emptyProxiesDir));
        ProxyManager emptyMgr = new ProxyManager(ai, providerHolder, empty, ai.store(), ai.jsonCodec(), ai.logger());
        assertTrue(emptyMgr.list().isEmpty(), "no proxies installed -> no hardcoded claude-code row");
    }

    @Test
    void routingReflectsWhetherTheInstalledProfileHasTiers() {
        assertTrue(statusOf(ROUTING_ID).routing, "non-empty tierOrder -> routing=true");
        assertFalse(statusOf(NO_ROUTING_ID).routing, "null profile -> routing=false");
    }

    @Test
    void displayNameComesFromTheInstalledPlugin() {
        assertEquals("Routing Proxy", statusOf(ROUTING_ID).displayName);
        assertEquals("No Routing Proxy", statusOf(NO_ROUTING_ID).displayName);
    }

    @Test
    void startOpensPortAndStopCloses() throws IOException {
        mgr.setPort(ROUTING_ID, 0); // ephemeral - avoid clashing with a live proxy on the default port
        ProxyManager.ProxyStatus s = mgr.start(ROUTING_ID);
        assertTrue(s.running, s.error);
        assertEquals(200, healthz(s.port));
        mgr.stop(ROUTING_ID);
        assertThrows(IOException.class, () -> healthz(s.port)); // connection refused after stop
    }

    @Test
    void startWithNoRoutingProfileCapturesErrorInsteadOfStarting() {
        ProxyManager.ProxyStatus s = mgr.start(NO_ROUTING_ID);
        assertFalse(s.running);
        assertNotNull(s.error);
    }

    @Test
    void setPortPersistsAndListReflects() {
        mgr.setPort(ROUTING_ID, 40555);
        ProxyManager.ProxyStatus s = statusOf(ROUTING_ID);
        assertEquals(40555, s.port);
        assertFalse(s.running);
    }

    @Test
    void startEnabledOnBootStartsOnlyEnabled() {
        mgr.setPort(ROUTING_ID, 0);        // ephemeral
        ProxyManager.ProxyStatus started = mgr.start(ROUTING_ID); // persists enabled=true + actual port
        int persistedPort = started.port;
        mgr.stopAll();                    // closes listeners, frees persistedPort, leaves persisted enabled flag
        mgr2 = new ProxyManager(ai, providerHolder, proxyHolder, ai.store(), ai.jsonCodec(), ai.logger());
        mgr2.startEnabledOnBoot();
        ProxyManager.ProxyStatus status = statusOf(mgr2, ROUTING_ID);
        assertTrue(status.running, status.error);
        assertEquals(persistedPort, status.port);
    }

    @Test
    void unknownIdThrows() {
        assertThrows(IllegalArgumentException.class, () -> mgr.start("nope"));
    }

    private ProxyManager.ProxyStatus statusOf(String id) {
        return statusOf(mgr, id);
    }

    private ProxyManager.ProxyStatus statusOf(ProxyManager m, String id) {
        for (ProxyManager.ProxyStatus s : m.list()) {
            if (id.equals(s.id)) return s;
        }
        return fail("no status for proxy " + id);
    }

    private int healthz(int port) throws IOException {
        URL url = new URL("http://127.0.0.1:" + port + "/healthz");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(1000);
        c.setReadTimeout(1000);
        c.setRequestMethod("GET");
        try {
            return c.getResponseCode();
        } finally {
            c.disconnect();
        }
    }

    private static void stageProviderJar(Path targetDir) throws IOException {
        String staged = System.getProperty("exampleserver.providersDir");
        assertNotNull(staged, "exampleserver.providersDir must be set by the Gradle test task");
        try (DirectoryStream<Path> s = Files.newDirectoryStream(Path.of(staged), "*.jar")) {
            for (Path jar : s) {
                Files.copy(jar, targetDir.resolve(jar.getFileName()));
                return;
            }
        }
        fail("no staged provider jar found in " + staged);
    }

    /**
     * Packages the already-compiled {@link RoutingProxyPlugin}/{@link NoRoutingProxyPlugin}
     * {@code .class} files (compiled as a normal part of this module's test-compile step) plus a
     * real {@code META-INF/services} registration into an actual jar, dropped into a "proxies"
     * directory, exactly the shape a real proxy plugin ships. No new Gradle staging is required:
     * unlike the provider fixture (built by the separate {@code :examples-provider} project), this
     * jar is assembled inline from classes already on the test classpath.
     */
    private static void stageProxyJar(Path proxiesDir) throws IOException {
        Path jarPath = proxiesDir.resolve("fixture-proxies.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(jarPath))) {
            writeClassEntry(jar, RoutingProxyPlugin.class);
            writeClassEntry(jar, NoRoutingProxyPlugin.class);
            jar.putNextEntry(new JarEntry("META-INF/services/" + ProxyPlugin.class.getName()));
            jar.write((RoutingProxyPlugin.class.getName() + "\n" + NoRoutingProxyPlugin.class.getName())
                    .getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }

    private static void writeClassEntry(JarOutputStream jar, Class<?> clazz) throws IOException {
        String path = clazz.getName().replace('.', '/') + ".class";
        try (InputStream in = ProxyManagerTest.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing compiled class on test classpath: " + path);
            jar.putNextEntry(new JarEntry(path));
            jar.write(in.readAllBytes());
            jar.closeEntry();
        }
    }

    /** Fixture proxy with a real routing profile (non-empty tierOrder, "echo" tier source). */
    public static final class RoutingProxyPlugin implements ProxyPlugin {
        @Override public String id() { return ROUTING_ID; }
        @Override public String displayName() { return "Routing Proxy"; }
        @Override public RoutingProfile profile() { return ServerProfile.echoTiers("proxy-manager-test-routing.json"); }
    }

    /** Fixture proxy declaring no routing profile: the start() guard path. */
    public static final class NoRoutingProxyPlugin implements ProxyPlugin {
        @Override public String id() { return NO_ROUTING_ID; }
        @Override public String displayName() { return "No Routing Proxy"; }
        @Override public RoutingProfile profile() { return null; }
    }
}
