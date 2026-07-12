package io.github.intisy.ai.core.http;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Transport-agnostic HTTP response value type. Java analog of the JS {@code AiResponse}
 * used by proxy handlers (see {@code libs/core-proxy/src/types.ts}).
 */
public class AiResponse {
    public int status;
    public Map<String, String> headers;
    public byte[] body;

    public AiResponse() {
    }

    public AiResponse(int status, Map<String, String> headers, byte[] body) {
        this.status = status;
        this.headers = headers;
        this.body = body;
    }

    public static AiResponse json(int status, String body) {
        Map<String, String> headers = new HashMap<>();
        headers.put("content-type", "application/json");
        return new AiResponse(status, headers, body.getBytes(StandardCharsets.UTF_8));
    }

    public static AiResponse text(int status, String body) {
        Map<String, String> headers = new HashMap<>();
        headers.put("content-type", "text/plain");
        return new AiResponse(status, headers, body.getBytes(StandardCharsets.UTF_8));
    }
}
