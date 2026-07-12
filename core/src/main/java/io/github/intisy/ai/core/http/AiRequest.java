package io.github.intisy.ai.core.http;

import java.util.HashMap;
import java.util.Map;

/**
 * Transport-agnostic HTTP request value type. Java analog of the JS {@code AiRequest}
 * used by proxy handlers (see {@code libs/core-proxy/src/types.ts}).
 */
public class AiRequest {
    public String method;
    public String url;
    public Map<String, String> headers;
    public byte[] body;

    public AiRequest() {
    }

    public AiRequest(String method, String url, Map<String, String> headers, byte[] body) {
        this.method = method;
        this.url = url;
        this.headers = headers;
        this.body = body;
    }

    /**
     * Deep-ish copy (new headers map + copied body array) so a handler can re-read the
     * body after another handler consumed it — the JS {@code request.clone()} analog.
     */
    public AiRequest clone() {
        Map<String, String> headersCopy = headers == null ? null : new HashMap<>(headers);
        byte[] bodyCopy = body == null ? null : body.clone();
        return new AiRequest(method, url, headersCopy, bodyCopy);
    }
}
