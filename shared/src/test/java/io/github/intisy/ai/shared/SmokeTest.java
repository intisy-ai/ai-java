package io.github.intisy.ai.shared;

import io.github.intisy.ai.shared.spi.Clock;
import io.github.intisy.ai.shared.spi.Env;
import io.github.intisy.ai.shared.spi.HttpClient;
import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.Logger;
import io.github.intisy.ai.shared.spi.Random;
import io.github.intisy.ai.shared.spi.Store;
import io.github.intisy.ai.shared.spi.http.HttpRequest;
import io.github.intisy.ai.shared.spi.http.HttpResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Trivial smoke test confirming the SPI surface compiles, links, and can be
 * exercised end-to-end with simple in-memory fakes.
 */
class SmokeTest {

    @Test
    void spiInterfacesAreUsable() {
        JsonCodec jsonCodec = new JsonCodec() {
            @Override
            public Object parse(String json) {
                return Map.of("ok", true);
            }

            @Override
            public String stringify(Object value) {
                return "{\"ok\":true}";
            }
        };
        assertEquals("{\"ok\":true}", jsonCodec.stringify(jsonCodec.parse("{}")));

        HttpClient httpClient = req -> {
            HttpResponse res = new HttpResponse();
            res.status = 200;
            res.headers = Map.of();
            res.body = req.body;
            return res;
        };
        HttpRequest request = new HttpRequest();
        request.method = "GET";
        request.url = "https://example.test";
        request.headers = Map.of();
        request.body = "hello";
        assertEquals(200, httpClient.send(request).status);
        assertEquals("hello", httpClient.send(request).body);

        Store store = new Store() {
            private final Map<String, String> data = new java.util.HashMap<>();

            @Override
            public String get(String key) {
                return data.get(key);
            }

            @Override
            public void put(String key, String value) {
                data.put(key, value);
            }

            @Override
            public boolean exists(String key) {
                return data.containsKey(key);
            }

            @Override
            public void delete(String key) {
                data.remove(key);
            }

            @Override
            public void update(String key, java.util.function.UnaryOperator<String> mutator) {
                data.put(key, mutator.apply(data.get(key)));
            }

            @Override
            public List<String> listKeys(String prefix) {
                return List.copyOf(data.keySet());
            }
        };
        store.put("accounts.json", "[]");
        assertNotNull(store.get("accounts.json"));

        Env env = name -> "value-of-" + name;
        assertEquals("value-of-FOO", env.get("FOO"));

        Clock clock = () -> 42L;
        assertEquals(42L, clock.now());

        Logger logger = msg -> {
        };
        logger.log("noop");

        Random random = () -> 0.5;
        assertEquals(0.5, random.next());
    }
}
