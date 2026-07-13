package io.github.intisy.ai.js;

import io.github.intisy.ai.shared.logic.HandlerResolvers;
import io.github.intisy.ai.shared.logic.Router;
import io.github.intisy.ai.shared.logic.RouterOptions;
import io.github.intisy.ai.shared.routing.ProxyHandler;
import io.github.intisy.ai.shared.routing.RoutingProfile;
import io.github.intisy.ai.shared.spi.HttpClient;
import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.Store;
import io.github.intisy.ai.shared.spi.http.HttpResponse;

import org.teavm.jso.JSExport;
import org.teavm.jso.core.JSPromise;
import org.teavm.jso.core.JSString;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * TeaVM JS export surface over shared's routing engine (Phase 2 Task 5 spike).
 *
 * <p>Step 1 of this class proves shared's {@code Router}/{@code ModelMap} (regex, maps,
 * StringBuilder-based UTF-8 decode, etc.) transpile and execute correctly under TeaVM —
 * fully synchronously, no JS-side I/O yet. Step 2 (see {@link JsHttpClientBridge}) adds the
 * actual decisive piece: a blocking-shaped {@code HttpClient.send} bridged to an async
 * JS-provided fetch-like function via TeaVM's {@code @Async}/{@code AsyncCallback} mechanism,
 * with the exported entrypoint returning a JS {@code Promise}.
 */
public final class AiJavaJs {
    private AiJavaJs() {
    }

    private static final String CONFIG_FILE = "router-test.json";

    /**
     * Synchronous smoke export: routes {@code requestJson} through a canned in-Java handler
     * (no HttpClient involved at all). {@code storeJson} is a JSON object of
     * {@code {storeKey: jsonStringValue}} used to seed the in-memory {@link Store} (e.g.
     * {@code {"router-test.json":"{\"modelMap\":{...}}"}}) — Store values are themselves
     * opaque JSON strings per the {@link Store} SPI contract.
     */
    @JSExport
    public static String routeJsonSync(String storeJson, String requestJson) {
        Store store = seedStore(storeJson);
        RouterOptions opts = baseOptions(store, syncHandlerRegistry());
        return Router.routeJson(requestJson, opts);
    }

    /**
     * THE decisive export: routes {@code requestJson} through shared's {@code Router}, whose
     * single registered provider handler forwards the request to an upstream via
     * {@link JsHttpClientBridge} — a blocking-shaped {@code HttpClient.send} actually backed
     * by the JS-provided {@code httpSend} async function (production: {@code fetch}; test
     * harness: a mocked, delayed resolve). Returns a JS {@code Promise<string>}: the whole
     * synchronous-looking Java call chain (routeJson -&gt; handle -&gt; HttpClient.send)
     * suspends at the {@code @Async} native boundary and resumes when the JS Promise the
     * caller supplied settles, all inside the {@link Thread} started below (TeaVM's own
     * {@code JSPromise.callAsync} uses the identical Thread-based mechanism internally).
     */
    @JSExport
    public static JSPromise<JSString> routeJsonAsync(JsHttpClientBridge.JsHttpSend httpSend,
                                                       String storeJson, String requestJson) {
        // Not JSPromise.callAsync(Callable<T>): its internal resolve.accept(result) is a
        // generic JSConsumer<T> call, which (per the JsHttpSend javadoc) leaks a raw jl_String
        // wrapper into the resolved value instead of a real JS string. Building the promise by
        // hand lets us convert explicitly via JSString.valueOf right before the resolve/reject
        // call — the actual CPS-suspension mechanism (a real Thread whose body reaches the
        // @Async awaitSend boundary) is identical to what callAsync does internally.
        return new JSPromise<>((resolve, reject) -> new Thread(() -> {
            try {
                JsonCodec json = new SimpleJsonCodec();
                Store store = seedStore(storeJson);
                HttpClient httpClient = new JsHttpClientBridge(httpSend, json);

                Map<String, ProxyHandler> registry = new HashMap<>();
                // The "provider handler" a real provider module would supply: forwards the
                // inbound request upstream via HttpClient.send (the async-bridged call).
                registry.put("test", (req, ctx) -> httpClient.send(req));

                RouterOptions opts = baseOptions(store, registry);
                opts.json = json;
                String result = Router.routeJson(requestJson, opts); // transitively async via httpClient.send
                resolve.accept(JSString.valueOf(result));
            } catch (Throwable e) {
                reject.accept(JSString.valueOf("routeJsonAsync failed: " + e));
            }
        }).start());
    }

    // -- shared wiring ------------------------------------------------------------

    static Store seedStore(String storeJson) {
        InMemoryStore store = new InMemoryStore();
        SimpleJsonCodec json = new SimpleJsonCodec();
        Object parsed = json.parse(storeJson);
        if (parsed instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) parsed).entrySet()) {
                if (e.getKey() != null && e.getValue() instanceof String) {
                    store.put(String.valueOf(e.getKey()), (String) e.getValue());
                }
            }
        }
        return store;
    }

    static RouterOptions baseOptions(Store store, Map<String, ProxyHandler> registry) {
        RouterOptions opts = new RouterOptions();
        opts.profile = testProfile();
        opts.resolveHandler = HandlerResolvers.fromRegistry(registry);
        opts.store = store;
        opts.json = new SimpleJsonCodec();
        opts.clock = System::currentTimeMillis;
        opts.log = msg -> {
        };
        opts.notify = (message, level) -> {
        };
        opts.listProviders = () -> new java.util.ArrayList<>(registry.keySet());
        opts.configDir = "";
        return opts;
    }

    // Mirrors shared's RouterTest.testProfile(): a minimal, valid RoutingProfile for a single
    // synthetic "test" tier/provider — no real Claude/native profile needed for this spike.
    static RoutingProfile testProfile() {
        RoutingProfile p = new RoutingProfile();
        p.configFile = CONFIG_FILE;
        p.routingKey = "providerRouting";
        p.tierSourceProvider = "test";
        p.tierOrder = Collections.singletonList("default");
        p.tierFallback = Collections.singletonList("default");
        p.tierRegex = Pattern.compile("^model-([a-z]+)-\\d");
        p.envPrefix = "AIJAVA";
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

    private static Map<String, ProxyHandler> syncHandlerRegistry() {
        Map<String, ProxyHandler> registry = new HashMap<>();
        registry.put("test", (req, ctx) -> {
            HttpResponse resp = new HttpResponse();
            resp.status = 200;
            resp.headers = new HashMap<>();
            resp.body = "served " + ctx.model;
            return resp;
        });
        return registry;
    }
}
