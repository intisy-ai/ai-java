package io.github.intisy.ai.examples.support;

import io.github.intisy.ai.jvm.backend.env.Env;

import java.util.HashMap;
import java.util.Map;

/**
 * An {@link Env} backed by a fixed map rather than the real process environment. Nothing in the
 * routing or account engines reads {@code Env} (it is JVM-side plumbing kept swappable, like the
 * other SPIs), so this exists purely to show that a server can inject its own configuration source
 * — a test never has to mutate real {@code System.getenv} to control what the app reads.
 */
public final class MapEnv implements Env {

    private final Map<String, String> values;

    public MapEnv(Map<String, String> values) {
        this.values = new HashMap<>(values);
    }

    @Override
    public String get(String name) {
        return values.get(name);
    }
}
