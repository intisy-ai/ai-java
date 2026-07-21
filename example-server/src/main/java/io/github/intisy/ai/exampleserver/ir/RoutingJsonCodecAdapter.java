package io.github.intisy.ai.exampleserver.ir;

import io.github.intisy.ai.shared.spi.JsonCodec;

/**
 * Adapts routing's {@link JsonCodec} SPI (parse/stringify) to core-ir's structurally identical
 * {@link io.github.intisy.ai.ir.spi.JsonCodec}, so ai-java's own {@code AnthropicTranslator}
 * instances ({@code ServerProfile}'s profile.translator, {@code MessagesAdmin}'s
 * console-chat IR front-door) can reuse the same codec instance the rest of this module already
 * threads (e.g. {@link io.github.intisy.ai.jvm.backend.json.GsonJsonCodec}) instead of duplicating
 * one. Mirrors the identically-shaped production adapters each provider module already carries
 * (e.g. claude-code-auth's {@code IrJsonCodecAdapter}, stub-auth's {@code RoutingJsonCodecAdapter}).
 */
public final class RoutingJsonCodecAdapter implements io.github.intisy.ai.ir.spi.JsonCodec {
    private final JsonCodec delegate;

    public RoutingJsonCodecAdapter(JsonCodec delegate) {
        this.delegate = delegate;
    }

    @Override
    public Object parse(String json) {
        return delegate.parse(json);
    }

    @Override
    public String stringify(Object value) {
        return delegate.stringify(value);
    }
}
