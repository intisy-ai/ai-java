package io.github.intisy.ai.jvm.backend.log;

import com.github.WildePizza.SimpleLogger;
import io.github.intisy.ai.shared.spi.Logger;

/**
 * Routes the shared {@link Logger} SPI to {@code intisy:simple-logger}'s {@link SimpleLogger}
 * (package {@code com.github.WildePizza} at the pinned {@code 1.12.7} tag).
 */
public class SimpleLoggerAdapter implements Logger {
    private final SimpleLogger logger;

    public SimpleLoggerAdapter() {
        this(new SimpleLogger());
    }

    public SimpleLoggerAdapter(SimpleLogger logger) {
        this.logger = logger;
    }

    @Override
    public void log(String msg) {
        logger.log(msg);
    }
}
