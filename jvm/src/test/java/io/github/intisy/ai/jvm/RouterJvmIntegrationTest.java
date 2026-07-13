package io.github.intisy.ai.jvm;

import io.github.intisy.ai.shared.logic.HandlerResolvers;
import io.github.intisy.ai.shared.logic.Router;
import io.github.intisy.ai.shared.logic.RouterOptions;
import io.github.intisy.ai.shared.routing.HandlerResolver;
import io.github.intisy.ai.shared.routing.ProxyHandler;
import io.github.intisy.ai.shared.routing.RoutingProfile;
import io.github.intisy.ai.shared.spi.http.HttpRequest;
import io.github.intisy.ai.shared.spi.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test (Task 7): wires the pure {@code shared} {@link Router} onto the REAL JVM
 * SPI implementations ({@link FileStore} + {@link GsonJsonCodec}) instead of the in-memory
 * test doubles {@code shared} itself uses ({@code InMemoryStore}/{@code TestJsonCodec} — see
 * {@code shared}'s {@code RouterTest}). Same fallback/exhaustion scenarios the old
 * {@code proxy} module's {@code ProxyServerTest} covered, now proving the shared routing
 * logic works against a real config directory on disk (seeded via {@link FileStore} +
 * {@link GsonJsonCodec}, not hand-written JSON strings).
 */
class RouterJvmIntegrationTest {

    private static final String CONFIG_FILE = "router-jvm-test.json";

    private static RoutingProfile testProfile() {
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

    /** Fake in-JVM providers: {@code rl} is always rate-limited, {@code ok} always serves. */
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

    private static RouterOptions optionsOn(FileStore store) {
        RouterOptions opts = new RouterOptions();
        opts.profile = testProfile();
        opts.resolveHandler = fakeResolver();
        opts.store = store;
        opts.json = new GsonJsonCodec();
        opts.clock = new SystemClock();
        opts.log = msg -> {
        };
        opts.notify = new JsonlNotifier(store.configFolder());
        opts.listProviders = () -> List.of("rl", "ok");
        opts.configDir = store.configFolder().toString();
        return opts;
    }

    private static HttpRequest post(String url, String body) {
        HttpRequest req = new HttpRequest();
        req.method = "POST";
        req.url = url;
        req.headers = new HashMap<>();
        req.body = body;
        return req;
    }

    @Test
    void ratelimitedPrimary_fallsBackToNextInChain_onRealFileStoreAndGson(@TempDir Path tmp) {
        FileStore store = new FileStore(tmp.resolve("config"));
        // Seed the model map via the REAL FileStore + GsonJsonCodec, not a hand-written string,
        // proving the JSON boundary SPI round-trips correctly through the routing engine.
        GsonJsonCodec json = new GsonJsonCodec();
        Map<String, Object> modelMap = new HashMap<>();
        Map<String, Object> opus = new HashMap<>();
        opus.put("provider", "rl");
        opus.put("model", "m-rl");
        Map<String, Object> opusFallback = new HashMap<>();
        opusFallback.put("provider", "ok");
        opusFallback.put("model", "m-ok");
        Map<String, Object> doc = new HashMap<>();
        doc.put("modelMap", Collections.singletonMap("opus", List.of(opus, opusFallback)));
        store.put(CONFIG_FILE, json.stringify(doc));

        RouterOptions opts = optionsOn(store);

        HttpResponse resp = Router.route(post("/v1/messages", "{}"), opts);

        assertEquals(200, resp.status);
        assertEquals("served m-ok", resp.body);

        // The fallback notice went through the JVM JsonlNotifier — assert the JSONL file
        // that a real deployment's PostToolUse hook would drain actually landed on disk.
        Path notifications = tmp.resolve("cache").resolve("auth-notifications.jsonl");
        assertTrue(Files.exists(notifications), "expected auth-notifications.jsonl to be written");
    }

    @Test
    void allEntriesRateLimited_synthesizesNative429_onRealFileStoreAndGson(@TempDir Path tmp) {
        FileStore store = new FileStore(tmp.resolve("config"));
        GsonJsonCodec json = new GsonJsonCodec();
        Map<String, Object> rl = new HashMap<>();
        rl.put("provider", "rl");
        rl.put("model", "m-rl");
        Map<String, Object> doc = new HashMap<>();
        doc.put("modelMap", Collections.singletonMap("opus", List.of(rl)));
        store.put(CONFIG_FILE, json.stringify(doc));

        RouterOptions opts = optionsOn(store);

        HttpResponse resp = Router.route(post("/v1/messages", "{}"), opts);

        assertEquals(429, resp.status);
        assertTrue(resp.body.contains("rate_limit_error"), resp.body);
    }

    @Test
    void health_returnsOk_onRealFileStoreAndGson(@TempDir Path tmp) {
        FileStore store = new FileStore(tmp.resolve("config"));
        RouterOptions opts = optionsOn(store);

        HttpResponse resp = Router.route(get("/health"), opts);

        assertEquals(200, resp.status);
        assertEquals("ok", resp.body);
    }

    private static HttpRequest get(String url) {
        HttpRequest req = new HttpRequest();
        req.method = "GET";
        req.url = url;
        req.headers = new HashMap<>();
        return req;
    }
}
