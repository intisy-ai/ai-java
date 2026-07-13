package io.github.intisy.ai.jvm;

import io.github.intisy.ai.shared.spi.Random;

import java.security.SecureRandom;

/**
 * {@code java.util.Random}/{@code SecureRandom}-backed {@link Random}: the real JVM
 * implementation of the random SPI. {@link #next()} returns a double in {@code [0, 1)},
 * matching JS's {@code Math.random()} contract that {@code shared}'s selection/backoff
 * logic is written against.
 */
public class SecureRandomAdapter implements Random {
    private final java.util.Random random;

    public SecureRandomAdapter() {
        this(new SecureRandom());
    }

    public SecureRandomAdapter(java.util.Random random) {
        this.random = random;
    }

    @Override
    public double next() {
        return random.nextDouble();
    }
}
