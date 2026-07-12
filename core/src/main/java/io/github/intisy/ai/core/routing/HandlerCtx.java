package io.github.intisy.ai.core.routing;

import java.util.function.Consumer;

/**
 * Context passed to a {@link ProxyHandler} for a single request.
 */
public class HandlerCtx {
    public String configDir;
    public Consumer<String> log;
    public String model;

    public HandlerCtx() {
    }

    public HandlerCtx(String configDir, Consumer<String> log, String model) {
        this.configDir = configDir;
        this.log = log;
        this.model = model;
    }
}
