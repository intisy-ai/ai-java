package io.github.intisy.ai.core.routing;

import io.github.intisy.ai.core.http.AiRequest;
import io.github.intisy.ai.core.http.AiResponse;

/**
 * Handles a single proxied request for a given provider.
 */
public interface ProxyHandler {
    AiResponse handle(AiRequest req, HandlerCtx ctx) throws Exception;
}
