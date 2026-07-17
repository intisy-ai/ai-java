package io.github.intisy.ai.examples.provider;

import io.github.intisy.ai.shared.routing.HandlerCtx;
import io.github.intisy.ai.shared.routing.Provider;
import io.github.intisy.ai.shared.spi.http.HttpRequest;
import io.github.intisy.ai.shared.spi.http.HttpResponse;

import java.util.HashMap;

/**
 * A healthy, realistic example {@link Provider}: it answers with a well-formed
 * Anthropic-messages-shaped JSON body that echoes back the model the router assigned to the
 * request (via {@link HandlerCtx#model}) plus a canned assistant message. This is the "it works"
 * half of the {@code :examples} showcase's fallback chain. It also answers {@code GET /v1/models}
 * with a canned catalog, the same discovery branch a real provider (claude/antigravity) gains —
 * this is what {@code RoutingAdmin.discover} exercises in tests, with no network involved. It
 * likewise answers {@code GET /v1/quota} with a canned accounts/quota catalog, the same branch a
 * real provider gains — this is what {@code QuotaAdmin.refresh} exercises in tests.
 *
 * <p>Shape discipline mirrors stub-auth's {@code StubProvider}: no gson, no reflection, no
 * {@code java.net}/{@code java.nio} — just hand-rolled JSON string building — so the jar stays
 * thin and the class transpiles cleanly. The {@code content}/{@code stop_reason}/{@code usage}
 * fields match what an Anthropic {@code /v1/messages} client expects, so a caller reading the
 * response never has to special-case "this came from an example provider".
 */
public final class EchoProvider implements Provider {

    /** The provider id this instance serves; matches the {@code provider} field in a model-map assignment. */
    public static final String ID = "echo";

    private static final String ASSISTANT_TEXT = "Echo provider handled your request";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public HttpResponse handle(HttpRequest request, HandlerCtx ctx) {
        if (request != null && "GET".equals(request.method) && "/v1/models".equals(request.url)) {
            return modelsResponse();
        }
        if (request != null && "GET".equals(request.method) && "/v1/quota".equals(request.url)) {
            return quotaResponse();
        }
        if (request != null && "GET".equals(request.method) && "/v1/config".equals(request.url)) {
            return configResponse();
        }
        if (request != null && "PUT".equals(request.method) && "/v1/config".equals(request.url)) {
            return putConfigResponse(request.body);
        }
        if (request != null && "GET".equals(request.method) && "/v1/oauth/params".equals(request.url)) {
            return oauthParamsResponse();
        }
        if (request != null && "POST".equals(request.method) && "/v1/oauth/exchange".equals(request.url)) {
            return oauthExchangeResponse(request.body);
        }

        String servedModel = ctx != null && ctx.model != null && !ctx.model.isEmpty()
                ? ctx.model
                : "echo-default";

        HttpResponse response = new HttpResponse();
        response.status = 200;
        response.headers = new HashMap<>();
        response.headers.put("content-type", "application/json");
        response.body = anthropicMessageBody(servedModel);
        return response;
    }

    // Last-written values object as raw JSON text (defaults shown). PUT replaces it; GET echoes it.
    // The fixture persists-and-echoes rather than parsing -- enough to prove ConfigAdmin's round-trip;
    // a real provider parses+validates+coerces against its schema and persists under configDir.
    private String valuesJson = "{\"greeting\":\"Echo provider handled your request\",\"verbose\":false}";

    private HttpResponse configResponse() {
        HttpResponse response = new HttpResponse();
        response.status = 200;
        response.headers = new HashMap<>();
        response.headers.put("content-type", "application/json");
        response.body = "{"
                + "\"groups\":[{\"title\":\"General\",\"fields\":["
                + "{\"key\":\"greeting\",\"label\":\"Greeting\",\"type\":\"string\"},"
                + "{\"key\":\"verbose\",\"label\":\"Verbose\",\"type\":\"bool\"}"
                + "]}],"
                + "\"values\":" + valuesJson
                + "}";
        return response;
    }

    private HttpResponse putConfigResponse(String body) {
        String extracted = extractJsonObject(body, "values");
        if (extracted != null) valuesJson = extracted;
        HttpResponse response = new HttpResponse();
        response.status = 200;
        response.headers = new HashMap<>();
        response.headers.put("content-type", "application/json");
        response.body = "{\"values\":" + valuesJson + "}";
        return response;
    }

    // Returns the raw JSON object text that is the value of `key` in `body` (from its opening '{'
    // to the matching '}', quote/escape-aware), or null if not found. Hand-rolled to keep the
    // fixture gson-free and transpilable.
    private static String extractJsonObject(String body, String key) {
        if (body == null) return null;
        String needle = "\"" + key + "\"";
        int k = body.indexOf(needle);
        if (k < 0) return null;
        int start = body.indexOf('{', k + needle.length());
        if (start < 0) return null;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < body.length(); i++) {
            char c = body.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return body.substring(start, i + 1);
            }
        }
        return null;
    }

    private static HttpResponse oauthParamsResponse() {
        HttpResponse response = new HttpResponse();
        response.status = 200;
        response.headers = new HashMap<>();
        response.headers.put("content-type", "application/json");
        response.body = "{"
                + "\"authorizeUrl\":\"https://echo.example/authorize\","
                + "\"clientId\":\"echo-client-id\","
                + "\"scopes\":\"openid email\","
                + "\"redirectPath\":\"/api/oauth/callback\","
                + "\"usesPkce\":true"
                + "}";
        return response;
    }

    // Fixture exchange: no network. Echoes the code into the refresh token so a test can prove the
    // code reached the provider. A real provider calls OAuthExchange.exchangeCode here.
    private HttpResponse oauthExchangeResponse(String body) {
        String code = extractStringField(body, "code");
        HttpResponse response = new HttpResponse();
        response.status = 200;
        response.headers = new HashMap<>();
        response.headers.put("content-type", "application/json");
        response.body = "{\"account\":{"
                + "\"id\":\"echo-oauth-user\","
                + "\"email\":\"echo-oauth@example.com\","
                + "\"refresh\":" + quote("echo-refresh-" + (code != null ? code : ""))
                + "}}";
        return response;
    }

    // Returns the string value of `key` in a flat JSON object (quote/escape-aware), or null.
    private static String extractStringField(String body, String key) {
        if (body == null) return null;
        String needle = "\"" + key + "\"";
        int k = body.indexOf(needle);
        if (k < 0) return null;
        int colon = body.indexOf(':', k + needle.length());
        if (colon < 0) return null;
        int i = colon + 1;
        while (i < body.length() && body.charAt(i) != '"') i++;   // skip to opening quote
        if (i >= body.length()) return null;
        i++;
        StringBuilder sb = new StringBuilder();
        boolean escaped = false;
        for (; i < body.length(); i++) {
            char c = body.charAt(i);
            if (escaped) { sb.append(c); escaped = false; }
            else if (c == '\\') escaped = true;
            else if (c == '"') break;
            else sb.append(c);
        }
        return sb.toString();
    }

    // Canned quota catalog: one active account with a single "5-hour" quota bucket -- shape
    // matches what QuotaAdmin.refresh expects back ({accounts:[{id,status,quota:[...]}]}).
    private static HttpResponse quotaResponse() {
        HttpResponse response = new HttpResponse();
        response.status = 200;
        response.headers = new HashMap<>();
        response.headers.put("content-type", "application/json");
        response.body = "{"
                + "\"accounts\":[{"
                + "\"id\":\"a1\","
                + "\"status\":\"active\","
                + "\"quota\":[{\"label\":\"5-hour\",\"remainingFraction\":0.8,\"resetTime\":123}]"
                + "}]"
                + "}";
        return response;
    }

    // Canned discovery catalog, matching the ids ServerSeeds already seeds for "echo" so the two
    // stay in sync for anyone comparing seeded vs. discovered.
    private static HttpResponse modelsResponse() {
        HttpResponse response = new HttpResponse();
        response.status = 200;
        response.headers = new HashMap<>();
        response.headers.put("content-type", "application/json");
        response.body = "{"
                + "\"models\":{"
                + "\"m-echo-opus\":{\"name\":\"Echo Opus\",\"limit\":{\"context\":200000,\"output\":64000}},"
                + "\"m-echo-haiku\":{\"name\":\"Echo Haiku\",\"limit\":{\"context\":200000,\"output\":64000}}"
                + "},"
                + "\"ranking\":[\"m-echo-opus\",\"m-echo-haiku\"]"
                + "}";
        return response;
    }

    // { id, type, role, model, content:[{type,text}], stop_reason, stop_sequence,
    //   usage:{input_tokens, output_tokens} } -- the non-streaming Anthropic messages shape.
    private static String anthropicMessageBody(String model) {
        String text = ASSISTANT_TEXT + " (served by " + model + ")";
        return "{"
                + "\"id\":\"msg_echo_0001\","
                + "\"type\":\"message\","
                + "\"role\":\"assistant\","
                + "\"model\":" + quote(model) + ","
                + "\"content\":[{\"type\":\"text\",\"text\":" + quote(text) + "}],"
                + "\"stop_reason\":\"end_turn\","
                + "\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":9}"
                + "}";
    }

    private static String quote(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append("\\u");
                        String hex = Integer.toHexString(c);
                        for (int pad = hex.length(); pad < 4; pad++) sb.append('0');
                        sb.append(hex);
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
