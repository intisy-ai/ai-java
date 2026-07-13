package io.github.intisy.ai.js;

import io.github.intisy.ai.shared.logic.HandlerResolvers;
import io.github.intisy.ai.shared.logic.ModelMap;
import io.github.intisy.ai.shared.logic.RateLimit;
import io.github.intisy.ai.shared.logic.RateLimitMath;
import io.github.intisy.ai.shared.logic.Router;
import io.github.intisy.ai.shared.logic.RouterOptions;
import io.github.intisy.ai.shared.logic.Selection;
import io.github.intisy.ai.shared.logic.Strategy;
import io.github.intisy.ai.shared.routing.Assignment;
import io.github.intisy.ai.shared.routing.ProxyHandler;
import io.github.intisy.ai.shared.routing.RoutingProfile;
import io.github.intisy.ai.shared.spi.HttpClient;
import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.Store;
import io.github.intisy.ai.shared.spi.http.HttpResponse;
import io.github.intisy.ai.shared.store.AccountStore;

import org.teavm.jso.JSExport;
import org.teavm.jso.core.JSPromise;
import org.teavm.jso.core.JSString;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
     * Task 6 integer-fidelity check: a bare parse+stringify round trip through
     * {@link SimpleJsonCodec} (the same codec {@link #routeJsonSync}/{@link #routeJsonAsync}
     * use internally), with no {@code Router} involved. Exists so the npm package's TS consumer
     * test can prove — through the actually-shipped export surface, not a JVM-only unit test —
     * that a whole-number JSON value (including one outside 32-bit {@code int} range, exercising
     * TeaVM's emulated {@code Long}) reserializes without a spurious trailing {@code .0}, i.e.
     * stays byte-compatible with the JVM {@code GsonJsonCodec} (LONG_OR_DOUBLE) output for the
     * same input.
     */
    @JSExport
    public static String jsonRoundTrip(String json) {
        JsonCodec codec = new SimpleJsonCodec();
        return codec.stringify(codec.parse(json));
    }

    /**
     * Phase 2 Task 7 (JVM&lt;-&gt;JS parity vectors) export: {@code RateLimitMath.calculateBackoffMs}
     * over the {@code jitter == false} exact-value path (the deterministic one; {@code jitter ==
     * true} consults an RNG and is intentionally out of scope for a byte-identical parity check).
     * {@code argsJson} is {@code {"attempt":int,"baseMs":long,"maxMs":long,"jitter":boolean}};
     * returns the bare JSON number result (a {@code Long}, so a whole value never gets a
     * spurious {@code .0} -- see {@link #jsonRoundTrip}'s javadoc for why that matters).
     */
    @JSExport
    public static String calculateBackoffMsJson(String argsJson) {
        JsonCodec json = new SimpleJsonCodec();
        Map<?, ?> args = (Map<?, ?>) json.parse(argsJson);
        int attempt = toInt(args.get("attempt"));
        long baseMs = toLong(args.get("baseMs"));
        long maxMs = toLong(args.get("maxMs"));
        boolean jitter = Boolean.TRUE.equals(args.get("jitter"));
        long result = RateLimitMath.calculateBackoffMs(attempt, baseMs, maxMs, jitter);
        return json.stringify(result);
    }

    /**
     * Phase 2 Task 7 export: {@code RateLimit.rateLimitResetMs} over a synthesized
     * {@code HttpResponse} built from {@code argsJson} = {@code {"headers":{...},"now":long}}.
     * Returns the bare JSON number result.
     */
    @JSExport
    public static String rateLimitResetMsJson(String argsJson) {
        JsonCodec json = new SimpleJsonCodec();
        Map<?, ?> args = (Map<?, ?>) json.parse(argsJson);
        long now = toLong(args.get("now"));

        Map<String, String> headers = new HashMap<>();
        Object headersObj = args.get("headers");
        if (headersObj instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) headersObj).entrySet()) {
                headers.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
        }
        HttpResponse resp = new HttpResponse();
        resp.status = 200;
        resp.headers = headers;
        resp.body = "";

        long result = RateLimit.rateLimitResetMs(resp, now);
        return json.stringify(result);
    }

    /**
     * Phase 2 Task 7 export: {@code ModelMap.resolveTiers} -- the highest-risk regex surface
     * for JVM/JS divergence (tier family extraction via {@code profile.tierRegex}).
     * {@code profileJson} supplies {@code tierSourceProvider}/{@code tierOrder}/
     * {@code tierFallback}/{@code tierRegex}; {@code storeJson} is a Store snapshot (typically
     * just a seeded {@code models.json}). Returns the resolved tier list as a JSON array.
     */
    @JSExport
    public static String resolveTiersJson(String profileJson, String storeJson) {
        JsonCodec json = new SimpleJsonCodec();
        Store store = seedStore(storeJson);
        RoutingProfile p = profileFromJson(json, profileJson);
        List<String> tiers = ModelMap.resolveTiers(store, json, p);
        return json.stringify(tiers);
    }

    /**
     * Phase 2 Task 7 export: {@code ModelMap.resolveModelMap} -- the heal/derive engine.
     * {@code profileJson} supplies {@code configFile}/{@code tierSourceProvider}/
     * {@code tierOrder}/{@code tierFallback}/{@code tierRegex}/{@code envPrefix}; {@code
     * storeJson} is a Store snapshot (the config file's {@code modelMap} plus {@code
     * models.json}). Returns {@code {tier: [{provider,model,name,derived}, ...]}} as JSON.
     */
    @JSExport
    public static String resolveModelMapJson(String profileJson, String storeJson) {
        JsonCodec json = new SimpleJsonCodec();
        Store store = seedStore(storeJson);
        RoutingProfile p = profileFromJson(json, profileJson);
        Map<String, List<Assignment>> eff = ModelMap.resolveModelMap(store, json, p);

        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<Assignment>> entry : eff.entrySet()) {
            List<Object> chain = new ArrayList<>();
            for (Assignment a : entry.getValue()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("provider", a.provider);
                m.put("model", a.model);
                m.put("name", a.name);
                m.put("derived", a.derived);
                chain.add(m);
            }
            out.put(entry.getKey(), chain);
        }
        return json.stringify(out);
    }

    // -- parity-export helpers ------------------------------------------------------

    private static RoutingProfile profileFromJson(JsonCodec json, String profileJson) {
        Map<?, ?> m = (Map<?, ?>) json.parse(profileJson);
        RoutingProfile p = new RoutingProfile();
        Object configFile = m.get("configFile");
        p.configFile = configFile instanceof String ? (String) configFile : null;
        p.routingKey = "providerRouting";
        p.tierSourceProvider = (String) m.get("tierSourceProvider");
        p.tierOrder = toStringList(m.get("tierOrder"));
        p.tierFallback = toStringList(m.get("tierFallback"));
        p.tierRegex = Pattern.compile((String) m.get("tierRegex"));
        p.envPrefix = (String) m.get("envPrefix");
        p.defaultContext = 200000;
        p.defaultOutput = 64000;
        return p;
    }

    private static List<String> toStringList(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List) {
            for (Object v : (List<?>) o) out.add(String.valueOf(v));
        }
        return out;
    }

    private static int toInt(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : 0;
    }

    private static long toLong(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0L;
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
     *
     * <p>Phase 3 Task 1: {@code jsStore} is no longer a one-shot JSON snapshot — it is the LIVE
     * JS store object itself, bridged via {@link JsStoreBridge}. The "test" provider handler
     * below additionally claims an account via {@code AccountStore}/{@link Selection} (a
     * round-robin pick over whatever accounts are seeded under the {@code "test"} provider in
     * {@code accounts.json}) purely to give the npm consumer test something real to observe:
     * the cursor {@link Selection} advances lives in the store the caller passed in, so a
     * second {@code routeJsonAsync} call reusing the same JS store object picks the NEXT
     * account, proving the mutation was written back rather than discarded. When no
     * {@code accounts.json}/{@code "test"} entry exists (e.g. every other existing test's
     * store), {@code Selection.selectIndex} sees an empty pool and this is a no-op — the
     * response is byte-identical to before this change.
     */
    @JSExport
    public static JSPromise<JSString> routeJsonAsync(JsHttpClientBridge.JsHttpSend httpSend,
                                                       JsStoreBridge.JsStore jsStore, String requestJson) {
        // Not JSPromise.callAsync(Callable<T>): its internal resolve.accept(result) is a
        // generic JSConsumer<T> call, which (per the JsHttpSend javadoc) leaks a raw jl_String
        // wrapper into the resolved value instead of a real JS string. Building the promise by
        // hand lets us convert explicitly via JSString.valueOf right before the resolve/reject
        // call — the actual CPS-suspension mechanism (a real Thread whose body reaches the
        // @Async awaitSend boundary) is identical to what callAsync does internally.
        return new JSPromise<>((resolve, reject) -> new Thread(() -> {
            try {
                JsonCodec json = new SimpleJsonCodec();
                Store store = new JsStoreBridge(jsStore); // LIVE — no snapshot, no discard on return
                HttpClient httpClient = new JsHttpClientBridge(httpSend, json);
                AccountStore accountStore = new AccountStore(store, json);

                Map<String, ProxyHandler> registry = new HashMap<>();
                // The "provider handler" a real provider module would supply: forwards the
                // inbound request upstream via HttpClient.send (the async-bridged call), after
                // claiming an account round-robin-style (see this method's javadoc).
                registry.put("test", (req, ctx) -> {
                    long now = System.currentTimeMillis();
                    String[] pickedId = new String[1];
                    accountStore.update("test", pool -> {
                        int idx = Selection.selectIndex(pool, null, now, Strategy.ROUND_ROBIN,
                                (a, l) -> RateLimitMath.isAvailable(a, l, now));
                        if (idx >= 0) pickedId[0] = pool.accounts.get(idx).id;
                    });
                    HttpResponse resp = httpClient.send(req);
                    if (pickedId[0] != null) {
                        Map<String, String> headers = resp.headers != null
                                ? new HashMap<>(resp.headers) : new HashMap<>();
                        headers.put("x-account-id", pickedId[0]);
                        resp.headers = headers;
                    }
                    return resp;
                });

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
