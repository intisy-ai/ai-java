package io.github.intisy.ai.examples.provider;

import io.github.intisy.ai.ir.IrRequest;
import io.github.intisy.ai.ir.IrResponse;
import io.github.intisy.ai.ir.spi.HandlerCtx;
import io.github.intisy.ai.auth.contracts.Provider;

/**
 * A hostile example {@link Provider}: {@link #handleIr} always throws a {@link NoClassDefFoundError}
 * instead of returning a response, standing in for a real provider's upstream path failing with a
 * {@code LinkageError}/{@code NoClassDefFoundError} (e.g. a classloader mismatch) rather than an
 * ordinary checked/unchecked exception. Proves {@code MessagesAdmin.send}/{@code ManagementApi}'s
 * hardening: an {@code Error} escaping a provider must degrade to a readable JSON error response,
 * not drop the HTTP connection. Packaged in the SAME jar as {@link EchoProvider}/{@link
 * AlwaysRateLimitedProvider}/{@link CtxCapturingProvider} (all four listed in
 * {@code META-INF/services/io.github.intisy.ai.auth.contracts.Provider}).
 */
public final class ThrowingProvider implements Provider {

    /** The provider id this instance serves. */
    public static final String ID = "throwing";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public IrResponse handleIr(IrRequest request, HandlerCtx ctx) {
        throw new NoClassDefFoundError("simulated upstream classloader failure");
    }
}
