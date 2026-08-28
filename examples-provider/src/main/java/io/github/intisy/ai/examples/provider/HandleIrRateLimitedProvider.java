package io.github.intisy.ai.examples.provider;

import io.github.intisy.ai.ir.IrRequest;
import io.github.intisy.ai.ir.IrResponse;
import io.github.intisy.ai.ir.spi.HandleIrException;
import io.github.intisy.ai.ir.spi.HandlerCtx;
import io.github.intisy.ai.auth.contracts.Provider;

import java.util.Collections;

/**
 * An example {@link Provider} whose {@link #handleIr} always throws {@link HandleIrException}
 * carrying a distinctive status/header/body/{@code retryAfterMs} -- the "it works" half of
 * {@link AlwaysRateLimitedProvider} for the IR path, proving a thrown {@code HandleIrException}'s
 * structured payload survives a caller that only sees the exception rather than a response object
 * carrying the status.
 *
 * <p>Packaged in the SAME jar as {@link EchoProvider}/{@link AlwaysRateLimitedProvider}/{@link
 * CtxCapturingProvider}/{@link ThrowingProvider} (all listed in
 * {@code META-INF/services/io.github.intisy.ai.auth.contracts.Provider}).
 */
public final class HandleIrRateLimitedProvider implements Provider {

    /** The provider id this instance serves. */
    public static final String ID = "handleir-ratelimited";

    /** The status the thrown exception carries. */
    public static final int STATUS = 429;
    /** The header name the thrown exception carries. */
    public static final String HEADER_NAME = "retry-after";
    /** The header value the thrown exception carries. */
    public static final String HEADER_VALUE = "7";
    /** The body the thrown exception carries. */
    public static final String BODY = "{"
            + "\"type\":\"error\","
            + "\"error\":{\"type\":\"rate_limit_error\",\"message\":\"handleIr rate limited\"}"
            + "}";
    /** The retry hint the thrown exception carries, in milliseconds. */
    public static final long RETRY_AFTER_MS = 45_000L;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public IrResponse handleIr(IrRequest request, HandlerCtx ctx) throws Exception {
        throw new HandleIrException(STATUS, Collections.singletonMap(HEADER_NAME, HEADER_VALUE), BODY, RETRY_AFTER_MS);
    }
}
