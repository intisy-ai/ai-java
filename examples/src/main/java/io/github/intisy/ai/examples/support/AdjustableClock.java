package io.github.intisy.ai.examples.support;

import io.github.intisy.ai.shared.spi.Clock;

/**
 * A deterministic {@link Clock} whose "now" is fixed but can be moved forward explicitly. Swapping
 * this in for the default {@code SystemClock} is what makes backoff/cooldown behavior reproducible:
 * every timestamp the router and account manager compute is derived from {@link #now()}, so a test
 * (or a demo run) sees the exact same instants every time, and {@link #advanceBy(long)} lets the
 * example show a cooldown actually elapsing without a real sleep.
 */
public final class AdjustableClock implements Clock {

    private long nowMs;

    public AdjustableClock(long startMs) {
        this.nowMs = startMs;
    }

    @Override
    public long now() {
        return nowMs;
    }

    /** Moves the clock forward by {@code deltaMs} (e.g. to step past a rate-limit reset time). */
    public void advanceBy(long deltaMs) {
        this.nowMs += deltaMs;
    }
}
