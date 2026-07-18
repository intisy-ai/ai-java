package io.github.intisy.ai.examples.provider;

import io.github.intisy.ai.shared.routing.HandlerCtx;
import io.github.intisy.ai.shared.routing.Provider;
import io.github.intisy.ai.shared.spi.http.HttpRequest;
import io.github.intisy.ai.shared.spi.http.HttpResponse;

/**
 * A hostile example {@link Provider}: {@link #handle} always throws a {@link NoClassDefFoundError}
 * instead of returning a response, standing in for a real provider's upstream path failing with a
 * {@code LinkageError}/{@code NoClassDefFoundError} (e.g. a classloader mismatch) rather than an
 * ordinary checked/unchecked exception. Proves {@code MessagesAdmin.send}/{@code ManagementApi}'s
 * hardening: an {@code Error} escaping a provider must degrade to a readable JSON error response,
 * not drop the HTTP connection. Packaged in the SAME jar as {@link EchoProvider}/{@link
 * AlwaysRateLimitedProvider}/{@link CtxCapturingProvider} (all four listed in
 * {@code META-INF/services/io.github.intisy.ai.shared.routing.Provider}).
 */
public final class ThrowingProvider implements Provider {

    /** The provider id this instance serves. */
    public static final String ID = "throwing";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public HttpResponse handle(HttpRequest request, HandlerCtx ctx) {
        throw new NoClassDefFoundError("simulated upstream classloader failure");
    }
}
