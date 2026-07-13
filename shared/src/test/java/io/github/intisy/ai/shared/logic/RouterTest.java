package io.github.intisy.ai.shared.logic;

import io.github.intisy.ai.shared.routing.HandlerResolver;
import io.github.intisy.ai.shared.routing.ProxyHandler;
import io.github.intisy.ai.shared.routing.RoutingProfile;
import io.github.intisy.ai.shared.spi.http.HttpRequest;
import io.github.intisy.ai.shared.spi.http.HttpResponse;
import io.github.intisy.ai.shared.store.InMemoryStore;
import io.github.intisy.ai.shared.store.TestJsonCodec;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PURE routing test for {@link Router#route} (Task 6): calls the engine directly, no socket,
 * no {@code HttpServer} — same scenarios as the old {@code ProxyServerTest} integration test,
 * with a fake {@link HandlerResolver} ({@code rl} always rate-limited, {@code ok} always
 * serves) and an in-memory {@code Store}.
 */
class RouterTest {

    private static final String CONFIG_FILE = "router-test.json";

    // TEST DATA ONLY — a real profile (e.g. Claude's) supplies its own tierOrder/nativeRateLimit.
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

    private static RouterOptions baseOptions(InMemoryStore store) {
        RouterOptions opts = new RouterOptions();
        opts.profile = testProfile();
        opts.resolveHandler = fakeResolver();
        opts.store = store;
        opts.json = new TestJsonCodec();
        opts.clock = () -> 1_000_000L;
        opts.log = msg -> {
        };
        opts.notify = (message, level) -> {
        };
        opts.listProviders = () -> List.of("rl", "ok");
        opts.configDir = "";
        return opts;
    }

    private static HttpRequest get(String url) {
        HttpRequest req = new HttpRequest();
        req.method = "GET";
        req.url = url;
        req.headers = new HashMap<>();
        return req;
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
    void health_returnsOk() {
        InMemoryStore store = new InMemoryStore();
        store.put(CONFIG_FILE, "{\"modelMap\":{\"opus\":[{\"provider\":\"rl\",\"model\":\"m-rl\"},{\"provider\":\"ok\",\"model\":\"m-ok\"}]}}");
        RouterOptions opts = baseOptions(store);

        HttpResponse resp = Router.route(get("/health"), opts);

        assertEquals(200, resp.status);
        assertEquals("ok", resp.body);
    }

    @Test
    void ratelimitedPrimary_fallsBackToNextInChain() {
        InMemoryStore store = new InMemoryStore();
        store.put(CONFIG_FILE, "{\"modelMap\":{\"opus\":[{\"provider\":\"rl\",\"model\":\"m-rl\"},{\"provider\":\"ok\",\"model\":\"m-ok\"}]}}");
        RouterOptions opts = baseOptions(store);

        HttpResponse resp = Router.route(post("/v1/messages", "{}"), opts);

        assertEquals(200, resp.status);
        assertEquals("served m-ok", resp.body);
    }

    @Test
    void allEntriesRateLimited_synthesizesNative429() {
        InMemoryStore store = new InMemoryStore();
        store.put(CONFIG_FILE, "{\"modelMap\":{\"opus\":[{\"provider\":\"rl\",\"model\":\"m-rl\"}]}}");
        RouterOptions opts = baseOptions(store);

        HttpResponse resp = Router.route(post("/v1/messages", "{}"), opts);

        assertEquals(429, resp.status);
        assertTrue(resp.body.contains("rate_limit_error"));
    }

    // -- routeJson smoke ---------------------------------------------------------

    @Test
    void routeJson_healthSmoke_roundTripsThroughJson() {
        InMemoryStore store = new InMemoryStore();
        store.put(CONFIG_FILE, "{\"modelMap\":{\"opus\":[{\"provider\":\"rl\",\"model\":\"m-rl\"},{\"provider\":\"ok\",\"model\":\"m-ok\"}]}}");
        RouterOptions opts = baseOptions(store);

        String requestJson = "{\"method\":\"GET\",\"url\":\"/health\",\"headers\":{},\"body\":\"\"}";
        String responseJson = Router.routeJson(requestJson, opts);

        assertTrue(responseJson.contains("\"status\":200"));
        assertTrue(responseJson.contains("\"body\":\"ok\""));
    }

    @Test
    void routeJson_fallbackSmoke_roundTripsThroughJson() {
        InMemoryStore store = new InMemoryStore();
        store.put(CONFIG_FILE, "{\"modelMap\":{\"opus\":[{\"provider\":\"rl\",\"model\":\"m-rl\"},{\"provider\":\"ok\",\"model\":\"m-ok\"}]}}");
        RouterOptions opts = baseOptions(store);

        String requestJson = "{\"method\":\"POST\",\"url\":\"/v1/messages\",\"headers\":{},\"body\":\"{}\"}";
        String responseJson = Router.routeJson(requestJson, opts);

        assertTrue(responseJson.contains("\"status\":200"));
        assertTrue(responseJson.contains("served m-ok"));
    }
}
