package io.github.intisy.ai.examples.support;

import io.github.intisy.ai.shared.logic.HandlerResolvers;
import io.github.intisy.ai.shared.routing.HandlerResolver;
import io.github.intisy.ai.shared.routing.ProxyHandler;
import io.github.intisy.ai.shared.spi.http.HttpResponse;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A pair of in-process handlers for the demos whose subject is storage/SPI swappability rather than
 * jar discovery: {@code rl} is always rate-limited (429), {@code ok} always serves. Wired via the
 * three-argument {@code AiJava.router(profile, resolver, listProviders)} overload — the same
 * escape hatch a server would use to supply its own {@link HandlerResolver} (e.g. a test double)
 * instead of the jar-backed {@code ProviderRegistry}.
 */
public final class InProcessProviders {

    private InProcessProviders() {
    }

    public static HandlerResolver resolver() {
        Map<String, ProxyHandler> registry = new HashMap<>();
        registry.put("rl", (request, ctx) -> {
            HttpResponse response = new HttpResponse();
            response.status = 429;
            response.headers = new HashMap<>();
            response.body = "";
            return response;
        });
        registry.put("ok", (request, ctx) -> {
            HttpResponse response = new HttpResponse();
            response.status = 200;
            response.headers = new HashMap<>();
            response.body = "served " + ctx.model;
            return response;
        });
        return HandlerResolvers.fromRegistry(registry);
    }

    public static List<String> ids() {
        return Arrays.asList("rl", "ok");
    }
}
