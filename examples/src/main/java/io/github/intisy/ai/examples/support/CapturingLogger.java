package io.github.intisy.ai.examples.support;

import io.github.intisy.ai.api.seam.Logger;

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

    /**
     * A logger that keeps every line instead of printing it.
     *
     * @param prefix prepended to each captured line
     */
    public CapturingLogger(String prefix) {
        this.prefix = prefix;
    }

    @Override
    public void info(String message) {
        lines.add(prefix + message);
    }

    @Override
    public void warn(String message) {
        lines.add(prefix + message);
    }

    @Override
    public void debug(String message) {
        lines.add(prefix + message);
    }

    @Override
    public void error(String message) {
        lines.add(prefix + message);
    }

    @Override
    public void error(String message, Object cause) {
        lines.add(prefix + message + " (" + cause + ")");
    }

    /** The captured lines (already prefixed), in order. */
    /** {@return every line this logger captured, in order} */
    public List<String> lines() {
        return Collections.unmodifiableList(lines);
    }
}
