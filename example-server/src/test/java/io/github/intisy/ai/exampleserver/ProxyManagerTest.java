package io.github.intisy.ai.exampleserver;

import io.github.intisy.ai.exampleserver.discovery.ProviderDiscovery;
import io.github.intisy.ai.exampleserver.discovery.ProviderRegistryHolder;
import io.github.intisy.ai.jvm.AiJava;
import io.github.intisy.ai.jvm.Storage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ProxyManagerTest {

    private AiJava ai;
    private ProviderRegistryHolder holder;
    private ProxyManager mgr;

    @BeforeEach
    void setUp(@TempDir Path providersDir, @TempDir Path configDir) throws IOException {
        stageProviderJar(providersDir);
        ai = AiJava.builder().storage(Storage.file(configDir)).build();
        holder = new ProviderRegistryHolder(ProviderDiscovery.resolve(providersDir));
        mgr = new ProxyManager(ai, holder, ai.store(), ai.jsonCodec(), ai.logger());
    }

    @AfterEach
    void tearDown() throws IOException {
        if (mgr != null) mgr.stopAll();
        if (holder != null && holder.get() != null) holder.get().close();
        if (ai != null) ai.close();
    }

    @Test
    void startOpensPortAndStopCloses() throws IOException {
        ProxyManager.ProxyStatus s = mgr.start("claude-code");
        assertTrue(s.running, s.error);
        assertEquals(200, healthz(s.port));
        mgr.stop("claude-code");
        assertThrows(IOException.class, () -> healthz(s.port)); // connection refused after stop
    }

    @Test
    void setPortPersistsAndListReflects() {
        mgr.setPort("opencode", 40555);
        ProxyManager.ProxyStatus s = statusOf("opencode");
        assertEquals(40555, s.port);
        assertFalse(s.running);
    }

    @Test
    void startEnabledOnBootStartsOnlyEnabled() {
        mgr.start("claude-code");            // persists enabled=true
        mgr.stopAll();                       // closes listeners, leaves persisted enabled flag
        ProxyManager mgr2 = new ProxyManager(ai, holder, ai.store(), ai.jsonCodec(), ai.logger());
        mgr2.startEnabledOnBoot();
        assertTrue(statusOf(mgr2, "claude-code").running);
        assertFalse(statusOf(mgr2, "opencode").running);
        mgr2.stopAll();
    }

    @Test
    void unknownAppThrows() {
        assertThrows(IllegalArgumentException.class, () -> mgr.start("nope"));
    }

    private ProxyManager.ProxyStatus statusOf(String app) {
        return statusOf(mgr, app);
    }

    private ProxyManager.ProxyStatus statusOf(ProxyManager m, String app) {
        for (ProxyManager.ProxyStatus s : m.list()) {
            if (app.equals(s.app)) return s;
        }
        return fail("no status for app " + app);
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
}
