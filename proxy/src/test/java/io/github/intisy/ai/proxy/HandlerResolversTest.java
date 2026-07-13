package io.github.intisy.ai.proxy;

import io.github.intisy.ai.core.http.AiRequest;
import io.github.intisy.ai.core.http.AiResponse;
import io.github.intisy.ai.core.routing.HandlerCtx;
import io.github.intisy.ai.core.routing.HandlerResolver;
import io.github.intisy.ai.core.routing.ProxyHandler;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class HandlerResolversTest {

    @Test
    void fromRegistry_resolvesRegisteredHandler() {
        // Create a demo handler that echoes "hi " + ctx.model
        ProxyHandler demoHandler = (req, ctx) ->
                AiResponse.text(200, "hi " + ctx.model);

        Map<String, ProxyHandler> registry = new HashMap<>();
        registry.put("demo", demoHandler);

        HandlerResolver resolver = HandlerResolvers.fromRegistry(registry);

        assertNotNull(resolver.resolve("demo"));
    }

    @Test
    void fromRegistry_handleInvocationReturnsCorrectResponse() throws Exception {
        ProxyHandler demoHandler = (req, ctx) ->
                AiResponse.text(200, "hi " + ctx.model);

        Map<String, ProxyHandler> registry = new HashMap<>();
        registry.put("demo", demoHandler);

        HandlerResolver resolver = HandlerResolvers.fromRegistry(registry);
        ProxyHandler handler = resolver.resolve("demo");

        AiRequest req = new AiRequest("GET", "http://test", new HashMap<>(), new byte[0]);
        HandlerCtx ctx = new HandlerCtx("/tmp/config", msg -> {}, "m1");

        AiResponse response = handler.handle(req, ctx);

        assertEquals(200, response.status);
        String body = new String(response.body, StandardCharsets.UTF_8);
        assertEquals("hi m1", body);
    }

    @Test
    void fromRegistry_unregisteredHandlerReturnsNull() {
        Map<String, ProxyHandler> registry = new HashMap<>();
        HandlerResolver resolver = HandlerResolvers.fromRegistry(registry);

        assertNull(resolver.resolve("nope"));
    }

    @Test
    void fromRegistry_defensivelyCopiesMap() {
        ProxyHandler demoHandler = (req, ctx) ->
                AiResponse.text(200, "hi");

        Map<String, ProxyHandler> registry = new HashMap<>();
        registry.put("demo", demoHandler);

        HandlerResolver resolver = HandlerResolvers.fromRegistry(registry);

        // Mutate the original map after creating the resolver
        registry.put("foo", (req, ctx) -> AiResponse.text(200, "foo"));
        registry.remove("demo");

        // The resolver should still have the original state
        assertNotNull(resolver.resolve("demo"));
        assertNull(resolver.resolve("foo"));
    }

    @Test
    void fromSupplier_re_readsMapOnEachResolve() {
        Map<String, ProxyHandler> registry = new HashMap<>();
        ProxyHandler demoHandler = (req, ctx) ->
                AiResponse.text(200, "hi");
        registry.put("demo", demoHandler);

        Supplier<Map<String, ProxyHandler>> supplier = () -> registry;
        HandlerResolver resolver = HandlerResolvers.fromSupplier(supplier);

        // Initially resolves
        assertNotNull(resolver.resolve("demo"));

        // Mutate the supplied map
        registry.put("foo", (req, ctx) -> AiResponse.text(200, "foo"));
        registry.remove("demo");

        // The resolver reflects the new state
        assertNull(resolver.resolve("demo"));
        assertNotNull(resolver.resolve("foo"));
    }

    @Test
    void fromSupplier_handlesNullMap() {
        Supplier<Map<String, ProxyHandler>> supplier = () -> null;
        HandlerResolver resolver = HandlerResolvers.fromSupplier(supplier);

        assertNull(resolver.resolve("anything"));
    }

    @Test
    void fromSupplier_handlesAbsentHandler() {
        Supplier<Map<String, ProxyHandler>> supplier = () -> new HashMap<>();
        HandlerResolver resolver = HandlerResolvers.fromSupplier(supplier);

        assertNull(resolver.resolve("nope"));
    }
}
