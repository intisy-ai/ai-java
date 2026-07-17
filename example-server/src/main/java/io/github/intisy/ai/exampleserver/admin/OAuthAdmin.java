package io.github.intisy.ai.exampleserver.admin;

import io.github.intisy.ai.exampleserver.discovery.ProviderRegistryHolder;
import io.github.intisy.ai.exampleserver.oauth.Pkce;
import io.github.intisy.ai.jvm.backend.store.FileStore;
import io.github.intisy.ai.shared.routing.HandlerCtx;
import io.github.intisy.ai.shared.routing.ProxyHandler;
import io.github.intisy.ai.shared.spi.Clock;
import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.Logger;
import io.github.intisy.ai.shared.spi.Store;
import io.github.intisy.ai.shared.spi.http.HttpRequest;
import io.github.intisy.ai.shared.spi.http.HttpResponse;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * UI-safe OAuth login administration: drives a full browser {@code authorization_code} + PKCE flow
 * against an installed provider's {@code /v1/oauth/params} + {@code /v1/oauth/exchange} conventions.
 * {@link #start} builds the authorize URL and stashes a single-use, short-TTL pending entry keyed by
 * {@code state}; {@link #callback} validates + consumes it, exchanges the code via the provider, and
 * seeds the account through {@link AccountAdmin}. Mirrors {@link QuotaAdmin}'s shape (encapsulates
 * the {@link Store}). Never logs {@code code}/{@code code_verifier}/tokens.
 */
public final class OAuthAdmin {
    private static final long PENDING_TTL_MS = 10 * 60 * 1000L;

    private final ProviderRegistryHolder holder;
    private final JsonCodec json;
    private final Logger log;
    private final AccountAdmin accounts;
    private final Clock clock;
    private final String configDir;
    private final SecureRandom rng = new SecureRandom();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public OAuthAdmin(Store store, JsonCodec json, ProviderRegistryHolder holder, Logger log,
                      AccountAdmin accounts, Clock clock) {
        this.holder = holder;
        this.json = json;
        this.log = log;
        this.accounts = accounts;
        this.clock = clock;
        this.configDir = store instanceof FileStore ? ((FileStore) store).configFolder().toString() : "";
    }

    public Map<String, Object> start(String providerId, String callbackBaseUrl) {
        Map<String, Object> params = asMap(json.parse(call(providerId, "GET", "/v1/oauth/params", null).body));
        if (params == null) throw new IllegalArgumentException("provider has no oauth params: " + providerId);

        String authorizeBase = stringOf(params.get("authorizeUrl"));
        String clientId = stringOf(params.get("clientId"));
        String scopes = stringOf(params.get("scopes"));
        String redirectPath = stringOf(params.get("redirectPath"));
        boolean usesPkce = Boolean.TRUE.equals(params.get("usesPkce"));
        if (authorizeBase == null || clientId == null) {
            throw new IllegalArgumentException("provider oauth params missing authorizeUrl/clientId");
        }
        String redirectUri = callbackBaseUrl + (redirectPath != null ? redirectPath : "/api/oauth/callback");

        String state = Pkce.state(rng);
        String verifier = usesPkce ? Pkce.verifier(rng) : null;

        StringBuilder url = new StringBuilder(authorizeBase);
        url.append(authorizeBase.contains("?") ? '&' : '?');
        url.append("response_type=code");
        url.append("&client_id=").append(enc(clientId));
        if (scopes != null) url.append("&scope=").append(enc(scopes));
        url.append("&redirect_uri=").append(enc(redirectUri));
        url.append("&state=").append(enc(state));
        if (usesPkce) {
            url.append("&code_challenge=").append(enc(Pkce.challengeS256(verifier)));
            url.append("&code_challenge_method=S256");
        }

        pending.put(state, new Pending(providerId, verifier, redirectUri, clock.now()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("authorizeUrl", url.toString());
        result.put("state", state);
        return result;
    }

    public Map<String, Object> callback(String code, String state) {
        Pending entry = state != null ? pending.remove(state) : null;   // single-use
        if (entry == null) {
            throw new IllegalArgumentException("unknown or expired oauth state");
        }
        if (clock.now() - entry.createdAt > PENDING_TTL_MS) {
            throw new IllegalArgumentException("expired oauth state");
        }

        String body = json.stringify(exchangeBody(code, entry.verifier, entry.redirectUri));
        HttpResponse response = call(entry.providerId, "POST", "/v1/oauth/exchange", body);
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
                entry.providerId,
                stringOf(account.get("id")),
                stringOf(account.get("email")),
                stringOf(account.get("refresh")),
                meta != null ? stringOf(meta.get("projectId")) : null,
                meta != null ? stringOf(meta.get("managedProjectId")) : null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("account", view);
        return result;
    }

    private static Map<String, Object> exchangeBody(String code, String verifier, String redirectUri) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        if (verifier != null) body.put("codeVerifier", verifier);
        body.put("redirectUri", redirectUri);
        return body;
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
            return handler.handle(request, new HandlerCtx(configDir, log, null));
        } catch (Exception e) {
            throw new IllegalArgumentException("oauth call failed: " + e.getMessage());
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s; // UTF-8 always supported
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    private static String stringOf(Object o) {
        return o instanceof String ? (String) o : null;
    }

    private static final class Pending {
        final String providerId;
        final String verifier;
        final String redirectUri;
        final long createdAt;

        Pending(String providerId, String verifier, String redirectUri, long createdAt) {
            this.providerId = providerId;
            this.verifier = verifier;
            this.redirectUri = redirectUri;
            this.createdAt = createdAt;
        }
    }
}
