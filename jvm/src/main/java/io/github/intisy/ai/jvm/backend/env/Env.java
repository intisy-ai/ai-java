package io.github.intisy.ai.jvm.backend.env;

/**
 * Environment-variable lookup SPI. Lives in {@code jvm} rather than the shared/routing/accounts
 * core: unlike {@code Store}/{@code HttpClient}/{@code JsonCodec}/{@code Clock}/{@code Logger}/
 * {@code Random} (all consumed by {@code Router}/{@code AccountManager}), nothing in the routing
 * or account engines ever calls {@code Env}. It exists purely as JVM-side plumbing, an env-var
 * seam for a future server kept swappable for tests the same way {@code Store}/{@code HttpClient}
 * etc. are, which is also why it belongs here rather than in a TeaVM-transpiled module.
 */
public interface Env {
    String get(String name);
}
