package io.github.intisy.ai.examples.support;

import io.github.intisy.ai.shared.spi.JsonCodec;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link JsonCodec} that delegates to a real codec (e.g. the default {@code GsonJsonCodec}) while
 * counting calls. It exists to demonstrate that the JSON boundary is a swappable SPI — a server that
 * prefers Jackson (or a hardened parser) just implements {@link JsonCodec} — without reimplementing
 * JSON parsing here; the call counts prove the injected codec is the one the engine actually used.
 */
public final class RecordingJsonCodec implements JsonCodec {

    private final JsonCodec delegate;
    private final AtomicInteger parseCount = new AtomicInteger();
    private final AtomicInteger stringifyCount = new AtomicInteger();

    public RecordingJsonCodec(JsonCodec delegate) {
        this.delegate = delegate;
    }

    @Override
    public Object parse(String json) {
        parseCount.incrementAndGet();
        return delegate.parse(json);
    }

    @Override
    public String stringify(Object value) {
        stringifyCount.incrementAndGet();
        return delegate.stringify(value);
    }

    public int parseCount() {
        return parseCount.get();
    }

    public int stringifyCount() {
        return stringifyCount.get();
    }
}
