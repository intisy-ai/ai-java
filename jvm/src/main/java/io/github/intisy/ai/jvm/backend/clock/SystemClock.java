package io.github.intisy.ai.jvm.backend.clock;

import io.github.intisy.ai.shared.spi.Clock;

/** {@code System.currentTimeMillis()}-backed {@link Clock}: the real JVM implementation of the clock SPI. */
public class SystemClock implements Clock {
    @Override
    public long now() {
        return System.currentTimeMillis();
    }
}
