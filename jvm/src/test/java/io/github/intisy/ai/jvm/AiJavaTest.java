package io.github.intisy.ai.jvm;

import io.github.intisy.ai.shared.logic.HandlerResolvers;
import io.github.intisy.ai.shared.routing.HandlerResolver;
import io.github.intisy.ai.shared.routing.ProxyHandler;
import io.github.intisy.ai.shared.routing.RoutingProfile;
import io.github.intisy.ai.shared.spi.Store;
import io.github.intisy.ai.shared.spi.http.HttpRequest;
import io.github.intisy.ai.shared.spi.http.HttpResponse;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 3: covers the {@link AiJava} builder facade + {@link Storage} factory. Reuses the same
 * fallback-routing scenario as {@link RouterJvmIntegrationTest}, but wired entirely through
 * {@code AiJava.builder()} instead of hand-assembling {@code RouterOptions}, and against both
 * a non-file backend ({@link Storage#memory()}) and a real database ({@link Storage#jdbc}) to
 * prove storage really is swappable rather than forced to files.
 */
class AiJavaTest {

    private static final String CONFIG_FILE = "ai-java-test.json";

    @Test
    void buildWithoutStorageThrows() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> AiJava.builder().build());
        assertEquals("storage backend is required; use Storage.file/memory/jdbc or your own Store",
                ex.getMessage());
    }

    @Test
    void onlyStorageSet_appliesJvmDefaultsForEverythingElse() {
        AiJava app = AiJava.builder().storage(Storage.memory()).build();

        assertTrue(app.httpClient() instanceof UrlConnectionHttpClient);
        assertTrue(app.jsonCodec() instanceof GsonJsonCodec);
        assertTrue(app.clock() instanceof SystemClock);
        assertTrue(app.logger() instanceof SimpleLoggerAdapter);
        assertTrue(app.random() instanceof SecureRandomAdapter);
        assertTrue(app.env() instanceof SystemEnv);
        assertNotNull(app.notifier(), "a memory-backed store still gets a (no-op) default notifier");
    }

    @Test
    void memoryStore_routesEndToEnd_withNoFileEverCreated(@TempDir Path tmp) throws IOException {
        Store store = Storage.memory();
        seedFallbackModelMap(store, new GsonJsonCodec());

        AiJava app = AiJava.builder().storage(store).build();
        AiJava.WiredRouter router = app.router(fallbackProfile(), fakeResolver(), () -> List.of("rl", "ok"));

        HttpResponse resp = router.route(post("/v1/messages", "{}"));

        assertEquals(200, resp.status);
        assertEquals("served m-ok", resp.body);
        // Storage.memory() takes no path at all, so nothing should have touched this scratch
        // directory - the config lives purely in the ConcurrentHashMap backing InMemoryStore.
        try (java.util.stream.Stream<Path> files = Files.list(tmp)) {
            assertTrue(files.findAny().isEmpty(), "memory-backed AiJava must not create any file");
        }
    }

    @Test
    void jdbcStore_routesEndToEnd() {
        DataSource h2 = newH2DataSource();
        Store store = Storage.jdbc(h2);
        seedFallbackModelMap(store, new GsonJsonCodec());

        AiJava app = AiJava.builder().storage(store).build();
        AiJava.WiredRouter router = app.router(fallbackProfile(), fakeResolver(), () -> List.of("rl", "ok"));

        HttpResponse resp = router.route(post("/v1/messages", "{}"));

        assertEquals(200, resp.status);
        assertEquals("served m-ok", resp.body);
    }

    @Test
    void jdbcStore_healthEndpoint_worksThroughTheBuilder() {
        AiJava app = AiJava.builder().storage(Storage.jdbc(newH2DataSource())).build();
        AiJava.WiredRouter router = app.router(fallbackProfile(), fakeResolver(), Collections::emptyList);

        HttpResponse resp = router.route(get("/health"));

        assertEquals(200, resp.status);
        assertEquals("ok", resp.body);
    }

    @Test
    void fileStore_defaultNotifier_isARealJsonlNotifier(@TempDir Path tmp) {
        AiJava app = AiJava.builder().storage(Storage.file(tmp.resolve("config"))).build();

        assertTrue(app.notifier() instanceof JsonlNotifier,
                "a file-backed store should default to the real JsonlNotifier, not the no-op");
    }

    // -- shared fixtures, mirroring RouterJvmIntegrationTest -------------------

    private static DataSource newH2DataSource() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:aijava-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }

    private static RoutingProfile fallbackProfile() {
        RoutingProfile p = new RoutingProfile();
        p.configFile = CONFIG_FILE;
        p.routingKey = "providerRouting";
        p.tierSourceProvider = "ok";
        p.tierOrder = Collections.singletonList("opus");
        p.tierFallback = Collections.singletonList("opus");
        p.tierRegex = Pattern.compile("^claude-([a-z]+)-\\d");
        p.envPrefix = "ANTHROPIC";
        p.defaultContext = 200000;
        p.defaultOutput = 64000;
        p.nativeRateLimit = info -> {
            RoutingProfile.Synth s = new RoutingProfile.Synth();
            s.status = 429;
            s.headers = new HashMap<>();
            s.body = "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"rate limited\"}}";
            return s;
        };
        return p;
    }

    /** {@code rl} is always rate-limited, {@code ok} always serves - forces the fallback path. */
    private static HandlerResolver fakeResolver() {
        Map<String, ProxyHandler> registry = new HashMap<>();
        registry.put("rl", (req, ctx) -> {
            HttpResponse resp = new HttpResponse();
            resp.status = 429;
            resp.headers = new HashMap<>();
            resp.body = "";
            return resp;
        });
        registry.put("ok", (req, ctx) -> {
            HttpResponse resp = new HttpResponse();
            resp.status = 200;
            resp.headers = new HashMap<>();
            resp.body = "served " + ctx.model;
            return resp;
        });
        return HandlerResolvers.fromRegistry(registry);
    }

    private static void seedFallbackModelMap(Store store, GsonJsonCodec json) {
        Map<String, Object> opus = new HashMap<>();
        opus.put("provider", "rl");
        opus.put("model", "m-rl");
        Map<String, Object> opusFallback = new HashMap<>();
        opusFallback.put("provider", "ok");
        opusFallback.put("model", "m-ok");
        Map<String, Object> doc = new HashMap<>();
        doc.put("modelMap", Collections.singletonMap("opus", List.of(opus, opusFallback)));
        store.put(CONFIG_FILE, json.stringify(doc));
    }

    private static HttpRequest post(String url, String body) {
        HttpRequest req = new HttpRequest();
        req.method = "POST";
        req.url = url;
        req.headers = new HashMap<>();
        req.body = body;
        return req;
    }

    private static HttpRequest get(String url) {
        HttpRequest req = new HttpRequest();
        req.method = "GET";
        req.url = url;
        req.headers = new HashMap<>();
        return req;
    }
}
