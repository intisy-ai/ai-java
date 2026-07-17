package io.github.intisy.ai.exampleserver.admin;

import io.github.intisy.ai.exampleserver.discovery.ProviderRegistryHolder;
import io.github.intisy.ai.jvm.backend.store.FileStore;
import io.github.intisy.ai.shared.routing.HandlerCtx;
import io.github.intisy.ai.shared.routing.ProxyHandler;
import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.Logger;
import io.github.intisy.ai.shared.spi.Store;
import io.github.intisy.ai.shared.spi.http.HttpRequest;
import io.github.intisy.ai.shared.spi.http.HttpResponse;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * UI-safe OAuth login administration (provider-authorize model): the installed provider builds its
 * own authorize URL (its PKCE, its {@code state} with the verifier packed in, its registered
 * redirect) via {@code GET /v1/oauth/authorize}; the operator completes the flow (paste the code)
 * and {@link #complete} relays it to {@code POST /v1/oauth/exchange} and seeds the account through
 * {@link AccountAdmin}. The server generates no PKCE/state and holds no pending state. Never logs
 * {@code code}/{@code state}/tokens.
 */
public final class OAuthAdmin {
    private final ProviderRegistryHolder holder;
    private final JsonCodec json;
    private final Logger log;
    private final AccountAdmin accounts;
    private final String configDir;
    private final Store store;

    public OAuthAdmin(Store store, JsonCodec json, ProviderRegistryHolder holder, Logger log,
                      AccountAdmin accounts) {
        this.holder = holder;
        this.json = json;
        this.log = log;
        this.accounts = accounts;
        this.configDir = store instanceof FileStore ? ((FileStore) store).configFolder().toString() : "";
        this.store = store;
    }

    /** The provider's {@code {authorizeUrl, completion, loopbackPort?, loopbackPath?}}. */
    public Map<String, Object> authorize(String providerId) {
        HttpResponse response = call(providerId, "GET", "/v1/oauth/authorize", null);
        if (response.status == 404) {
            throw new IllegalArgumentException("provider has no oauth surface: " + providerId);
        }
        if (response.status / 100 != 2) {
            throw new IllegalArgumentException("provider returned " + response.status + ": " + response.body);
        }
        Map<String, Object> params = asMap(json.parse(response.body));
        if (params == null || stringOf(params.get("authorizeUrl")) == null) {
            throw new IllegalArgumentException("provider returned no authorizeUrl");
        }
        return params;
    }

    /** Relays {@code {code,state}} to the provider's exchange and seeds the returned account. */
    public Map<String, Object> complete(String providerId, String code, String state) {
        Map<String, Object> reqBody = new LinkedHashMap<>();
        reqBody.put("code", code);
        reqBody.put("state", state != null ? state : "");
        HttpResponse response = call(providerId, "POST", "/v1/oauth/exchange", json.stringify(reqBody));
        if (response.status / 100 != 2) {
            throw new IllegalArgumentException("provider returned " + response.status + ": " + response.body);
        }
        Map<String, Object> parsed = asMap(json.parse(response.body));
        Map<String, Object> account = parsed != null ? asMap(parsed.get("account")) : null;
        if (account == null) {
            throw new IllegalArgumentException("provider exchange returned no account");
        }
        Map<String, Object> meta = asMap(account.get("meta"));
        AccountAdmin.AccountView view = accounts.addToken(
                providerId,
                stringOf(account.get("id")),
                stringOf(account.get("email")),
                stringOf(account.get("refresh")),
                meta != null ? stringOf(meta.get("projectId")) : null,
                meta != null ? stringOf(meta.get("managedProjectId")) : null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("account", view);
        return result;
    }

    private HttpResponse call(String providerId, String method, String url, String body) {
        ProxyHandler handler = holder.asHandlerResolver().resolve(providerId);
        if (handler == null) {
            throw new IllegalArgumentException("unknown provider: " + providerId);
        }
        HttpRequest request = new HttpRequest();
        request.method = method;
        request.url = url;
        request.headers = new LinkedHashMap<>();
        request.body = body;
        try {
            return handler.handle(request, new HandlerCtx(configDir, store, log, null));
        } catch (Exception e) {
            throw new IllegalArgumentException("oauth call failed: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    private static String stringOf(Object o) {
        return o instanceof String ? (String) o : null;
    }
}
