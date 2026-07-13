package io.github.intisy.ai.jvm;

import io.github.intisy.ai.shared.spi.Env;

/** {@code System.getenv}-backed {@link Env}: the real JVM implementation of the env SPI. */
public class SystemEnv implements Env {
    @Override
    public String get(String name) {
        return System.getenv(name);
    }
}
