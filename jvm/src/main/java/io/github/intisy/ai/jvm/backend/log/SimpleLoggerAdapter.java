package io.github.intisy.ai.jvm.backend.log;

import io.github.intisy.ai.shared.spi.Logger;

import java.util.logging.Level;

/**
 * Routes the shared {@link Logger} SPI to {@code java.util.logging} (built into the JDK, zero
 * external dependency). The SPI exposes a single untyped {@link Logger#log(String)} call with no
 * level parameter, so every message is emitted at {@link Level#INFO} through a JUL logger named
 * after this class; JUL's own default console handler prints it.
 */
public class SimpleLoggerAdapter implements Logger {
    private final java.util.logging.Logger logger;

    public SimpleLoggerAdapter() {
        this(java.util.logging.Logger.getLogger(SimpleLoggerAdapter.class.getName()));
    }

    public SimpleLoggerAdapter(java.util.logging.Logger logger) {
        this.logger = logger;
    }

    @Override
    public void log(String msg) {
        logger.log(Level.INFO, msg);
    }
}
