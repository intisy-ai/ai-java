package io.github.intisy.ai.exampleserver;

import io.github.intisy.ai.exampleserver.discovery.ProviderDiscovery;
import io.github.intisy.ai.exampleserver.discovery.ProviderRegistryHolder;
import io.github.intisy.ai.jvm.AiJava;
import io.github.intisy.ai.jvm.Storage;
import io.github.intisy.ai.shared.routing.RoutingProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ProxyServerTest {

    private static final String CONFIG_FILE = "proxy-server-routing.json";
    private AiJava ai;
    private ProviderRegistryHolder holder;
    private ProxyServer proxy;

    @BeforeEach
    void setUp(@TempDir Path providersDir) throws IOException {
        stageProviderJar(providersDir);
        ai = AiJava.builder().storage(Storage.memory()).build();
        ServerSeeds.seedEcho(ai.store(), ai.jsonCodec(), CONFIG_FILE);
        holder = new ProviderRegistryHolder(ProviderDiscovery.resolve(providersDir));
        RoutingProfile profile = ServerProfile.echoTiers(CONFIG_FILE);
        AiJava.WiredRouter router = ai.router(profile,
                id -> holder.asHandlerResolver().resolve(id), holder::listProviderIds);
        proxy = ProxyServer.start(router, 0);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (proxy != null) proxy.stop();
        if (holder != null && holder.get() != null) holder.get().close();
        if (ai != null) ai.close();
    }

    @Test
    void healthzServed() throws IOException {
        Response r = get("/healthz");
        assertEquals(200, r.status, r.body);
    }

    @Test
    void v1RoutesToProvider() throws IOException {
        Response r = get("/v1/models");
        assertEquals(200, r.status, r.body);
        assertTrue(r.body.contains("m-echo") || r.body.contains("models"), r.body);
    }

    @Test
    void dashboardAndApiNotServed() throws IOException {
        assertEquals(404, get("/").status);          // no dashboard on a proxy port
        assertEquals(404, get("/api/proxies").status); // no management API on a proxy port
    }

    private static void stageProviderJar(Path targetDir) throws IOException {
        String staged = System.getProperty("exampleserver.providersDir");
        assertNotNull(staged, "exampleserver.providersDir must be set by the Gradle test task");
        try (DirectoryStream<Path> s = Files.newDirectoryStream(Path.of(staged), "*.jar")) {
            for (Path jar : s) { Files.copy(jar, targetDir.resolve(jar.getFileName())); return; }
        }
        fail("no staged provider jar found in " + staged);
    }

    private Response get(String path) throws IOException {
        URL url = new URL("http://127.0.0.1:" + proxy.port() + path);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("GET");
        int status = c.getResponseCode();
        InputStream is = status < 400 ? c.getInputStream() : c.getErrorStream();
        String text = "";
        if (is != null) try (Scanner sc = new Scanner(is, "UTF-8").useDelimiter("\\A")) { text = sc.hasNext() ? sc.next() : ""; }
        return new Response(status, text);
    }

    private static final class Response {
        final int status; final String body;
        Response(int status, String body) { this.status = status; this.body = body; }
    }
}
