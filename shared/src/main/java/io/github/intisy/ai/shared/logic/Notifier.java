package io.github.intisy.ai.shared.logic;

/**
 * SPI-style user-notice sink invoked by {@link Router} on heal/fallback/exhaustion events.
 * Unlike the old JVM-only {@code Notify} (which appended JSONL lines to a cache file), this
 * is a plain callback — the caller (JVM daemon, TeaVM host, test) owns delivery.
 */
public interface Notifier {
    void notify(String message, String level);
}
