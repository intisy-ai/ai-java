package io.github.intisy.ai.shared.routing;

import io.github.intisy.ai.shared.spi.http.HttpRequest;
import io.github.intisy.ai.shared.spi.http.HttpResponse;

/**
 * Handles a single proxied request for a given provider.
 */
public interface ProxyHandler {
    HttpResponse handle(HttpRequest req, HandlerCtx ctx) throws Exception;
}
