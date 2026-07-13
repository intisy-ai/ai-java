package io.github.intisy.ai.shared.routing;

import io.github.intisy.ai.shared.spi.Logger;

/**
 * Context passed to a {@link ProxyHandler} for a single request.
 */
public class HandlerCtx {
    public String configDir;
    public Logger log;
    public String model;

    public HandlerCtx() {
    }

    public HandlerCtx(String configDir, Logger log, String model) {
        this.configDir = configDir;
        this.log = log;
        this.model = model;
    }
}
