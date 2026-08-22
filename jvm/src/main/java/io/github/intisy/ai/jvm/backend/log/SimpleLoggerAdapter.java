package io.github.intisy.ai.jvm.backend.log;

import io.github.intisy.ai.api.seam.Logger;

import java.util.logging.Level;

/**
 * Routes the shared {@link Logger} seam to {@code java.util.logging} (built into the JDK, zero
 * external dependency) through a JUL logger named after this class, so JUL's own default console
 * handler prints it.
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
    public void info(String message) {
        logger.log(Level.INFO, message);
    }

    @Override
    public void warn(String message) {
        logger.log(Level.WARNING, message);
    }

    @Override
    public void debug(String message) {
        logger.log(Level.FINE, message);
    }

    @Override
    public void error(String message) {
        logger.log(Level.SEVERE, message);
    }

    @Override
    public void error(String message, Object cause) {
        if (cause instanceof Throwable) {
            logger.log(Level.SEVERE, message, (Throwable) cause);
        } else {
            logger.log(Level.SEVERE, message + " (" + cause + ")");
        }
    }
}
