package io.github.intisy.ai.jvm;

import io.github.intisy.ai.jvm.backend.json.GsonJsonCodec;
import io.github.intisy.ai.jvm.backend.store.InMemoryStore;
import io.github.intisy.ai.shared.logic.ModelMap;
import io.github.intisy.ai.shared.logic.RateLimit;
import io.github.intisy.ai.shared.select.RateLimitMath;
import io.github.intisy.ai.shared.routing.Assignment;
import io.github.intisy.ai.shared.routing.RoutingProfile;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 2 Task 7: JVM half of the JVM&lt;-&gt;JS parity harness. Loads vector files from this
 * module's own {@code src/test/resources/parity/} (originally {@code shared/src/test/resources/
 * parity/}, restored here in Phase 4 Task 3 now that {@code shared}/{@code js} relocated out of
 * ai-java into the core-proxy/core-auth submodules and their JS-side parity tests moved with
 * them), and runs each vector through the routing/select engine's {@code RateLimitMath}/
 * {@code RateLimit}/{@code ModelMap} (now sourced from the {@code :routing}/{@code :accounts}
 * submodule projects) directly,
 * using the PRODUCTION {@link GsonJsonCodec} and this module's {@link InMemoryStore} -- the
 * exact combination the {@code jvm} artifact ships. A passing run here plus a passing run of
 * the JS test on the identical vectors is the correctness guarantee: the same shared logic
 * produces IDENTICAL outputs on the JVM and on TeaVM-compiled JS.
 *
 * <p>Vector comparisons deliberately avoid whole-string JSON equality for object-shaped
 * results: {@link GsonJsonCodec} deserializes JSON objects (parsed as {@code Object}) into a
 * {@code LinkedTreeMap} that iterates in key-sorted order, not source insertion order, so a
 * raw string round-trip of a multi-key object would spuriously differ in key order from the
 * JS side's order-preserving {@code Map}. Comparing structured values field-by-field (or via
 * {@code Set} equality for key sets) sidesteps that JVM-codec-specific quirk entirely -- it is
 * not a JVM&lt;-&gt;JS logic divergence, just an artifact of using {@code Object.class} as the
 * gson target type.
 */
class ParityVectorsTest {

    private static final GsonJsonCodec JSON = new GsonJsonCodec();

    private static String readResource(String path) {
        try (InputStream in = ParityVectorsTest.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing resource: " + path);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> loadVectors(String name) {
        return (List<Object>) JSON.parse(readResource("/parity/" + name));
    }

    private static List<String> stringList(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List) {
            for (Object v : (List<?>) o) out.add(String.valueOf(v));
        }
        return out;
    }

    // -- calculateBackoffMs ---------------------------------------------------

    @Test
    void backoffVectors_matchExpected() {
        for (Object o : loadVectors("backoff.json")) {
            Map<?, ?> v = (Map<?, ?>) o;
            int attempt = ((Number) v.get("attempt")).intValue();
            long baseMs = ((Number) v.get("baseMs")).longValue();
            long maxMs = ((Number) v.get("maxMs")).longValue();
            boolean jitter = Boolean.TRUE.equals(v.get("jitter"));
            long expected = ((Number) v.get("expectedMs")).longValue();

            long actual = RateLimitMath.calculateBackoffMs(attempt, baseMs, maxMs, jitter);
            assertEquals(expected, actual, "vector id=" + v.get("id"));
        }
    }

    // -- rateLimitResetMs ---------------------------------------------------

    @Test
    void rateLimitResetVectors_matchExpected() {
        for (Object o : loadVectors("rate-limit-reset.json")) {
            Map<?, ?> v = (Map<?, ?>) o;
            long now = ((Number) v.get("now")).longValue();
            long expected = ((Number) v.get("expectedMs")).longValue();

            Map<String, String> headers = new LinkedHashMap<>();
            Object h = v.get("headers");
            if (h instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) h).entrySet()) {
                    headers.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
            io.github.intisy.ai.shared.spi.http.HttpResponse resp = new io.github.intisy.ai.shared.spi.http.HttpResponse();
            resp.status = 200;
            resp.headers = headers;
            resp.body = "";

            long actual = RateLimit.rateLimitResetMs(resp, now);
            assertEquals(expected, actual, "vector id=" + v.get("id"));
        }
    }

    // -- resolveTiers (regex tier extraction) ---------------------------------------------------

    @Test
    void tierExtractionVectors_matchExpected() {
        for (Object o : loadVectors("tier-extraction.json")) {
            Map<?, ?> v = (Map<?, ?>) o;
            RoutingProfile p = new RoutingProfile();
            p.tierSourceProvider = (String) v.get("tierSourceProvider");
            p.tierOrder = stringList(v.get("tierOrder"));
            p.tierFallback = stringList(v.get("tierFallback"));
            p.tierRegex = Pattern.compile((String) v.get("tierRegex"));
            p.envPrefix = "TEST";

            InMemoryStore store = new InMemoryStore();
            List<String> modelIds = stringList(v.get("modelIds"));
            Map<String, Object> models = new LinkedHashMap<>();
            for (String id : modelIds) models.put(id, new LinkedHashMap<>());
            Map<String, Object> providerEntry = new LinkedHashMap<>();
            providerEntry.put("models", models);
            providerEntry.put("ranking", modelIds);
            Map<String, Object> cache = new LinkedHashMap<>();
            cache.put(p.tierSourceProvider, providerEntry);
            store.put("models.json", JSON.stringify(cache));

            List<String> expected = stringList(v.get("expectedTiers"));
            List<String> actual = ModelMap.resolveTiers(store, JSON, p);
            assertEquals(expected, actual, "vector id=" + v.get("id"));
        }
    }

    // -- resolveModelMap (heal/derive) ---------------------------------------------------

    @Test
    void healDeriveVectors_matchExpected() {
        for (Object o : loadVectors("heal-derive.json")) {
            Map<?, ?> v = (Map<?, ?>) o;
            Map<?, ?> profileJson = (Map<?, ?>) v.get("profile");
            RoutingProfile p = new RoutingProfile();
            p.configFile = (String) profileJson.get("configFile");
            p.tierSourceProvider = (String) profileJson.get("tierSourceProvider");
            p.tierOrder = stringList(profileJson.get("tierOrder"));
            p.tierFallback = stringList(profileJson.get("tierFallback"));
            p.tierRegex = Pattern.compile((String) profileJson.get("tierRegex"));
            p.envPrefix = (String) profileJson.get("envPrefix");

            InMemoryStore store = new InMemoryStore();
            Map<?, ?> storeJson = (Map<?, ?>) v.get("store");
            for (Map.Entry<?, ?> e : storeJson.entrySet()) {
                store.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }

            Map<String, List<Assignment>> actual = ModelMap.resolveModelMap(store, JSON, p);
            Map<?, ?> expected = (Map<?, ?>) v.get("expected");
            String id = String.valueOf(v.get("id"));

            assertEquals(expected.keySet(), actual.keySet(), "vector id=" + id + " tier keys");
            for (Object tierKeyObj : expected.keySet()) {
                String tierKey = String.valueOf(tierKeyObj);
                List<?> expectedChain = (List<?>) expected.get(tierKeyObj);
                List<Assignment> actualChain = actual.get(tierKey);
                assertEquals(expectedChain.size(), actualChain.size(), "vector id=" + id + " tier=" + tierKey + " chain length");
                for (int i = 0; i < expectedChain.size(); i++) {
                    Map<?, ?> exp = (Map<?, ?>) expectedChain.get(i);
                    Assignment act = actualChain.get(i);
                    String where = "vector id=" + id + " tier=" + tierKey + "[" + i + "]";
                    assertEquals(exp.get("provider"), act.provider, where + ".provider");
                    assertEquals(exp.get("model"), act.model, where + ".model");
                    assertEquals(exp.get("name"), act.name, where + ".name");
                    assertEquals(exp.get("derived"), act.derived, where + ".derived");
                }
            }
        }
    }

    // -- JSON integer fidelity ---------------------------------------------------

    @Test
    void jsonIntegerFidelityVectors_matchExpected() {
        for (Object o : loadVectors("json-integer-fidelity.json")) {
            Map<?, ?> v = (Map<?, ?>) o;
            String input = (String) v.get("input");
            String actual = JSON.stringify(JSON.parse(input));
            String id = String.valueOf(v.get("id"));

            for (String substr : stringList(v.get("expectContains"))) {
                assertTrue(actual.contains(substr), "vector id=" + id + ": expected output to contain \"" + substr + "\" but got " + actual);
            }
            for (String substr : stringList(v.get("expectNotContains"))) {
                assertTrue(!actual.contains(substr), "vector id=" + id + ": expected output NOT to contain \"" + substr + "\" but got " + actual);
            }
        }
    }
}
