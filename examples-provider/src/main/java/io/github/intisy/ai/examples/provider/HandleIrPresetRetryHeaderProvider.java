package io.github.intisy.ai.examples.provider;

import io.github.intisy.ai.ir.IrRequest;
import io.github.intisy.ai.ir.IrResponse;
import io.github.intisy.ai.ir.spi.HandleIrException;
import io.github.intisy.ai.ir.spi.HandlerCtx;
import io.github.intisy.ai.auth.contracts.Provider;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An example {@link Provider} whose {@link #handleIr} always throws {@link HandleIrException}
 * with {@code x-hub-retry-after-ms} ALREADY set among its headers, and a DIFFERENT {@code
 * retryAfterMs} value -- proving a caller's reconstruction of the thrown exception must not
 * clobber a header the thrower already set. Complements {@link HandleIrRateLimitedProvider},
 * which leaves the header unset so the caller's own injection is exercised instead.
 *
 * <p>Packaged in the SAME jar as {@link EchoProvider}/{@link AlwaysRateLimitedProvider}/{@link
 * CtxCapturingProvider}/{@link ThrowingProvider}/{@link HandleIrRateLimitedProvider} (all listed
 * in {@code META-INF/services/io.github.intisy.ai.auth.contracts.Provider}).
 */
public final class HandleIrPresetRetryHeaderProvider implements Provider {

    /** The provider id this instance serves. */
    public static final String ID = "handleir-retry-header-preset";

    public static final int STATUS = 429;
    public static final String PRESET_HEADER_VALUE = "999";
    public static final String BODY = "{"
            + "\"type\":\"error\","
            + "\"error\":{\"type\":\"rate_limit_error\",\"message\":\"already has a retry header\"}"
            + "}";
    // Deliberately different from PRESET_HEADER_VALUE, so a clobber would be caught by a test
    // asserting the header still reads PRESET_HEADER_VALUE.
    public static final long RETRY_AFTER_MS = 123_456L;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public IrResponse handleIr(IrRequest request, HandlerCtx ctx) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-hub-retry-after-ms", PRESET_HEADER_VALUE);
        throw new HandleIrException(STATUS, headers, BODY, RETRY_AFTER_MS);
    }
}
