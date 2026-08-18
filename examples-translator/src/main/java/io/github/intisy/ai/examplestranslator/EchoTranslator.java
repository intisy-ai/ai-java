package io.github.intisy.ai.examplestranslator;

import io.github.intisy.ai.ir.IrRequest;
import io.github.intisy.ai.ir.IrResponse;
import io.github.intisy.ai.ir.IrStopReason;
import io.github.intisy.ai.ir.IrUsage;
import io.github.intisy.ai.ir.spi.StreamDecoder;
import io.github.intisy.ai.ir.spi.StreamEncoder;
import io.github.intisy.ai.ir.spi.Translator;
import io.github.intisy.ai.ir.stream.IrStreamEvent;

import java.util.Collections;
import java.util.List;

/**
 * Fixture {@link Translator}: no vendor wire format, just a fixed model name round-tripped
 * through a small hand-rolled JSON shape. Proves the {@code ServiceLoader} discovery loop
 * without depending on any real vendor translator.
 */
public final class EchoTranslator implements Translator {

    public static final String MODEL = "echo-model";

    @Override
    public IrRequest decodeRequest(String wireJson) {
        IrRequest request = new IrRequest();
        request.model = MODEL;
        return request;
    }

    @Override
    public String encodeRequest(IrRequest request) {
        return "{\"model\":\"" + MODEL + "\"}";
    }

    @Override
    public IrResponse decodeResponse(String wireJson) {
        IrResponse response = new IrResponse();
        response.model = MODEL;
        response.id = "echo-response";
        response.stopReason = IrStopReason.END_TURN;
        response.usage = new IrUsage(0, 0, null, null);
        return response;
    }

    @Override
    public String encodeResponse(IrResponse response) {
        return "{\"model\":\"" + MODEL + "\"}";
    }

    @Override
    public StreamDecoder newStreamDecoder() {
        return new StreamDecoder() {
            @Override
            public List<IrStreamEvent> decode(String chunk) {
                return Collections.emptyList();
            }
        };
    }

    @Override
    public StreamEncoder newStreamEncoder() {
        return new StreamEncoder() {
            @Override
            public String encode(IrStreamEvent event) {
                return "";
            }
        };
    }
}
