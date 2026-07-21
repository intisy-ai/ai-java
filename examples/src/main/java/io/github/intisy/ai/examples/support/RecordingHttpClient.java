package io.github.intisy.ai.examples.support;

import io.github.intisy.ai.shared.spi.HttpClient;
import io.github.intisy.ai.shared.spi.http.HttpRequest;
import io.github.intisy.ai.shared.spi.http.HttpResponse;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * An {@link HttpClient} that delegates to a real client (e.g. the default {@code
 * UrlConnectionHttpClient}) while counting the requests it sends. Injecting this proves the HTTP
 * boundary is swappable: a server that wants connection pooling, proxies, or its own retry policy
 * just implements {@link HttpClient}, and the count confirms the injected client is what performed
 * the OAuth refresh call.
 */
public final class RecordingHttpClient implements HttpClient {

    private final HttpClient delegate;
    private final AtomicInteger sendCount = new AtomicInteger();

    public RecordingHttpClient(HttpClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public HttpResponse send(HttpRequest request) {
        sendCount.incrementAndGet();
        return delegate.send(request);
    }

    public int sendCount() {
        return sendCount.get();
    }
}
