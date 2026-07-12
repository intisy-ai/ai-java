package io.github.intisy.ai.core.manager;

import java.util.Map;

/**
 * The injectable outbound-HTTP seam: everything in this module that needs to reach a network
 * endpoint (currently just {@code TokenRefresh.refresh}) goes through this interface instead of
 * calling {@code HttpURLConnection}/etc. directly, so tests can inject a fake and stay
 * network-free and deterministic. See {@link UrlConnectionFetcher} for the real Java-8 impl.
 */
public interface HttpFetcher {
    /** POSTs {@code form} as {@code application/x-www-form-urlencoded} to {@code url}. */
    Resp post(String url, Map<String, String> form) throws Exception;

    /** Minimal HTTP response: status code + raw body text. */
    final class Resp {
        public final int status;
        public final String body;

        public Resp(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }
}
