package io.github.intisy.ai.examples.provider;

import io.github.intisy.ai.shared.routing.AccountQuota;
import io.github.intisy.ai.shared.routing.AuthorizeInfo;
import io.github.intisy.ai.shared.routing.ConfigField;
import io.github.intisy.ai.shared.routing.ConfigGroup;
import io.github.intisy.ai.shared.routing.ConfigSchema;
import io.github.intisy.ai.shared.routing.ConfigurableProvider;
import io.github.intisy.ai.shared.routing.HandlerCtx;
import io.github.intisy.ai.shared.routing.ModelCatalogProvider;
import io.github.intisy.ai.shared.routing.ModelInfo;
import io.github.intisy.ai.shared.routing.OAuthProvider;
import io.github.intisy.ai.shared.routing.Provider;
import io.github.intisy.ai.shared.routing.QuotaBar;
import io.github.intisy.ai.shared.routing.QuotaProvider;
import io.github.intisy.ai.shared.spi.http.HttpRequest;
import io.github.intisy.ai.shared.spi.http.HttpResponse;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A healthy, realistic example {@link Provider}: it answers with a well-formed
 * Anthropic-messages-shaped JSON body that echoes back the model the router assigned to the
 * request (via {@link HandlerCtx#model}) plus a canned assistant message. This is the "it works"
 * half of the {@code :examples} showcase's fallback chain. It also implements every optional
 * typed capability ({@link ModelCatalogProvider}, {@link QuotaProvider}, {@link
 * ConfigurableProvider}, {@link OAuthProvider}) with canned data -- the test vehicle
 * {@code RoutingAdmin.discover}/{@code QuotaAdmin.refresh}/{@code ConfigAdmin}/{@code OAuthAdmin}
 * exercise, with no network involved and no fabricated HTTP request.
 *
 * <p>Shape discipline mirrors stub-auth's {@code StubProvider}: no gson, no reflection, no
 * {@code java.net}/{@code java.nio} -- just typed POJOs / hand-rolled JSON string building for the
 * messages echo -- so the jar stays thin and the class transpiles cleanly. The {@code
 * content}/{@code stop_reason}/{@code usage} fields match what an Anthropic {@code /v1/messages}
 * client expects, so a caller reading the response never has to special-case "this came from an
 * example provider".
 */
public final class EchoProvider implements Provider, ConfigurableProvider, ModelCatalogProvider,
        QuotaProvider, OAuthProvider {

    /** The provider id this instance serves; matches the {@code provider} field in a model-map assignment. */
    public static final String ID = "echo";

    private static final String ASSISTANT_TEXT = "Echo provider handled your request";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public HttpResponse handle(HttpRequest request, HandlerCtx ctx) {
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

    // -- ConfigurableProvider: a simple in-memory Map is enough for this fixture; a real provider
    // parses+validates+coerces against its schema and persists under configDir. --

    private final Map<String, Object> values = new LinkedHashMap<>();
    {
        values.put("greeting", ASSISTANT_TEXT);
        values.put("verbose", false);
    }

    @Override
    public ConfigSchema configSchema(HandlerCtx ctx) {
        ConfigField greeting = new ConfigField("greeting", "Greeting", "text", null, null);
        ConfigField verbose = new ConfigField("verbose", "Verbose", "bool", null, null);
        ConfigGroup general = new ConfigGroup("General", Arrays.asList(greeting, verbose));
        return new ConfigSchema(Collections.singletonList(general));
    }

    @Override
    public Map<String, Object> getConfigValues(HandlerCtx ctx) {
        return new LinkedHashMap<>(values);
    }

    @Override
    public Map<String, Object> putConfigValues(HandlerCtx ctx, Map<String, Object> newValues) {
        if (newValues != null) {
            values.putAll(newValues);
        }
        return new LinkedHashMap<>(values);
    }

    // -- ModelCatalogProvider: canned catalog, matching the ids ServerSeeds already seeds for
    // "echo" so the two stay in sync for anyone comparing seeded vs. discovered. List order =
    // ranking. --

    @Override
    public List<ModelInfo> models(HandlerCtx ctx) {
        return Arrays.asList(
                new ModelInfo("m-echo-opus", "Echo Opus", 200000, 64000),
                new ModelInfo("m-echo-haiku", "Echo Haiku", 200000, 64000));
    }

    // -- QuotaProvider: THREE accounts -- two active accounts sharing the "5-hour" pool
    // (remainingFraction 0.8 and 0.4, so QuotaAdmin.combined's mean is 0.6) and one errored account
    // with no quota bars at all (excluded from the mean but still counted in
    // combined.accountCount -- the whole point of the per-account AccountQuota shape). --

    @Override
    public List<AccountQuota> quota(HandlerCtx ctx) {
        AccountQuota a1 = new AccountQuota("a1", null, "active",
                Collections.singletonList(new QuotaBar("5-hour", 0.8, "123")));
        AccountQuota a2 = new AccountQuota("a2", null, "active",
                Collections.singletonList(new QuotaBar("5-hour", 0.4, "123")));
        AccountQuota a3 = new AccountQuota("a3", null, "error", Collections.emptyList());
        return Arrays.asList(a1, a2, a3);
    }

    // -- OAuthProvider: fixture exchange, no network. Echoes the code into the refresh token so a
    // test can prove the code reached the provider. A real provider calls OAuthExchange.exchangeCode
    // here. --

    @Override
    public AuthorizeInfo authorize(HandlerCtx ctx) {
        String url = "https://echo.example/authorize?response_type=code&client_id=echo-client-id"
                + "&code_challenge=echo-challenge&code_challenge_method=S256&state=echo-state";
        return new AuthorizeInfo(url, "paste", null, null, null);
    }

    @Override
    public Map<String, Object> exchange(HandlerCtx ctx, String body) {
        String code = extractStringField(body, "code");
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("id", "echo-oauth-user");
        account.put("email", "echo-oauth@example.com");
        account.put("refresh", "echo-refresh-" + (code != null ? code : ""));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("account", account);
        return result;
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
