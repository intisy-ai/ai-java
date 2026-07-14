package io.github.intisy.ai.examples.support;

import io.github.intisy.ai.shared.spi.http.HttpRequest;

import java.util.HashMap;

/** Small builders for the {@link HttpRequest}s the demos route, so each demo isn't repeating boilerplate. */
public final class Requests {

    private Requests() {
    }

    /** A POST whose JSON body names the requested {@code model} (what the router classifies into a tier). */
    public static HttpRequest messages(String model) {
        HttpRequest request = new HttpRequest();
        request.method = "POST";
        request.url = "/v1/messages";
        request.headers = new HashMap<>();
        request.body = "{\"model\":\"" + model + "\"}";
        return request;
    }

    public static HttpRequest get(String url) {
        HttpRequest request = new HttpRequest();
        request.method = "GET";
        request.url = url;
        request.headers = new HashMap<>();
        return request;
    }
}
