package io.github.intisy.ai.examples.support;

import io.github.intisy.ai.shared.spi.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A {@link Logger} that prefixes every line and keeps them in memory. Injecting this in place of
 * the default {@code SimpleLoggerAdapter} shows two things at once: that logging is a swappable SPI,
 * and (because the router logs its rate-limit/fallback decisions through it) exactly what the engine
 * decided during a routed request.
 */
public final class CapturingLogger implements Logger {

    private final String prefix;
    private final List<String> lines = new ArrayList<>();

    public CapturingLogger(String prefix) {
        this.prefix = prefix;
    }

    @Override
    public void log(String message) {
        lines.add(prefix + message);
    }

    /** The captured lines (already prefixed), in order. */
    public List<String> lines() {
        return Collections.unmodifiableList(lines);
    }
}
