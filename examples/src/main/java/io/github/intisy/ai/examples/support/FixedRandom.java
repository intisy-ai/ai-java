package io.github.intisy.ai.examples.support;

import io.github.intisy.ai.shared.spi.Random;

/**
 * A {@link Random} that always returns the same value in {@code [0, 1)}. The account manager uses
 * {@code random.next()} only for backoff jitter, so pinning it makes {@code reportError}'s computed
 * cooldown fully deterministic — the example can print (and a test can assert) the exact resume
 * time instead of "somewhere in a jittered range".
 */
public final class FixedRandom implements Random {

    private final double value;

    public FixedRandom(double value) {
        this.value = value;
    }

    @Override
    public double next() {
        return value;
    }
}
