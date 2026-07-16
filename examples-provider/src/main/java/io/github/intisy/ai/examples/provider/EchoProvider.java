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
 * this is what {@code RoutingAdmin.discover} exercises in tests, with no network involved.
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
