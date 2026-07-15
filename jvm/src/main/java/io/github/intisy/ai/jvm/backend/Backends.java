package io.github.intisy.ai.jvm.backend;

import io.github.intisy.ai.shared.spi.Store;

/**
 * Factory helpers for {@link Backend}. {@link #defaults(Store)} is the "just give me the JVM
 * defaults for everything except storage" entry point; for anything finer, use
 * {@link Backend#builder()}.
 */
public final class Backends {

    private Backends() {
    }

    /**
     * A {@link Backend} with the given store and every platform SPI defaulted to its JVM
     * implementation. Its {@link Backend#notifier()} is {@code null} so the host resolves the
     * store-derived default (a {@code JsonlNotifier} for a file store, a no-op otherwise).
     */
    public static Backend defaults(Store store) {
        return Backend.builder().store(store).build();
    }
}
