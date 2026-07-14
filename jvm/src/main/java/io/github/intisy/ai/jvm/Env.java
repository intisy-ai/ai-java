package io.github.intisy.ai.jvm;

/**
 * Environment-variable lookup SPI. Lives in {@code jvm} rather than the shared/routing/accounts
 * core: unlike {@code Store}/{@code HttpClient}/{@code JsonCodec}/{@code Clock}/{@code Logger}/
 * {@code Random} (all consumed by {@code Router}/{@code AccountManager} and relocated to
 * core-proxy's {@code :routing}/core-auth's {@code :accounts} in Phase 4 Tasks 1-2), nothing in
 * the routing or account engines ever calls {@code Env} — it was carried in ai-java's builder
 * purely as JVM-side plumbing (an env-var seam for a future server, kept swappable for tests the
 * same way {@code Store}/{@code HttpClient} etc. are). Task 1's core-proxy relocation correctly
 * left it out for that reason; it belongs here, not in a TeaVM-transpiled module.
 */
public interface Env {
    String get(String name);
}
