package io.github.intisy.ai.shared.spi.http;

import java.util.Map;

public class HttpRequest {
    public String method;
    public String url;
    public Map<String, String> headers;
    public String body;
}
