package io.github.intisy.ai.shared.spi;

import io.github.intisy.ai.shared.spi.http.HttpRequest;
import io.github.intisy.ai.shared.spi.http.HttpResponse;

/**
 * Blocking-shaped HTTP boundary; implementations handle any async plumbing internally.
 */
public interface HttpClient {
    HttpResponse send(HttpRequest req);
}
