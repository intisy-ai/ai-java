package io.github.intisy.ai.shared.spi.http;

import java.util.Map;

public class HttpResponse {
    public int status;
    public Map<String, String> headers;
    public String body;
}
