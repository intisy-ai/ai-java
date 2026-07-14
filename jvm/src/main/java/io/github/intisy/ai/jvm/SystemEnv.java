package io.github.intisy.ai.jvm;

/** {@code System.getenv}-backed {@link Env}: the real JVM implementation of the env SPI. */
public class SystemEnv implements Env {
    @Override
    public String get(String name) {
        return System.getenv(name);
    }
}
