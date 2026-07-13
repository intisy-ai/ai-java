package io.github.intisy.ai.shared.logic;

/**
 * Account selection strategy, ported from the string literals in
 * {@code libs/core-auth/src/selection.ts} ("round-robin" / "sticky" / "hybrid").
 */
public enum Strategy {
    ROUND_ROBIN,
    STICKY,
    HYBRID
}
