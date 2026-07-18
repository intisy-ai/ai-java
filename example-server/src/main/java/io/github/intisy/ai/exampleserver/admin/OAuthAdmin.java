package io.github.intisy.ai.exampleserver.admin;

import io.github.intisy.ai.exampleserver.discovery.ProviderRegistryHolder;
import io.github.intisy.ai.jvm.backend.store.FileStore;
import io.github.intisy.ai.shared.routing.AuthorizeInfo;
import io.github.intisy.ai.shared.routing.HandlerCtx;
import io.github.intisy.ai.shared.routing.OAuthProvider;
import io.github.intisy.ai.shared.routing.Provider;
import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.Logger;
import io.github.intisy.ai.shared.spi.Store;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * UI-safe OAuth login administration (provider-authorize model): the installed provider builds its
 * own authorize URL (its PKCE, its {@code state} with the verifier packed in, its registered
 * redirect) via the typed {@link OAuthProvider} capability; the operator completes the flow (paste
 * the code) and {@link #complete} relays it to the provider's typed {@code exchange} and seeds the
 * account through {@link AccountAdmin}. The server generates no PKCE/state and holds no pending
 * state. Never logs {@code code}/{@code state}/tokens.
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

    /** The provider's {@code {authorizeUrl, completion, state?, loopbackPort?, loopbackPath?}}. */
    public Map<String, Object> authorize(String providerId) {
        Provider p = holder.get(providerId);
        if (p == null) {
            throw new IllegalArgumentException("unknown provider: " + providerId);
        }
        if (!(p instanceof OAuthProvider)) {
            throw new IllegalArgumentException("provider has no oauth surface: " + providerId);
        }

        HandlerCtx ctx = new HandlerCtx(configDir, store, log, null);
        AuthorizeInfo info = ((OAuthProvider) p).authorize(ctx);
        if (info == null || info.authorizeUrl == null) {
            throw new IllegalArgumentException("provider returned no authorizeUrl");
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("authorizeUrl", info.authorizeUrl);
        params.put("completion", info.completion);
        if (info.state != null) params.put("state", info.state);
        if (info.loopbackPort != null) params.put("loopbackPort", info.loopbackPort);
        if (info.loopbackPath != null) params.put("loopbackPath", info.loopbackPath);
        return params;
    }

    /** Relays {@code {code,state}} to the provider's exchange and seeds the returned account. */
    public Map<String, Object> complete(String providerId, String code, String state) {
        Provider p = holder.get(providerId);
        if (p == null) {
            throw new IllegalArgumentException("unknown provider: " + providerId);
        }
        if (!(p instanceof OAuthProvider)) {
            throw new IllegalArgumentException("provider has no oauth surface: " + providerId);
        }

        Map<String, Object> reqBody = new LinkedHashMap<>();
        reqBody.put("code", code);
        reqBody.put("state", state != null ? state : "");
        HandlerCtx ctx = new HandlerCtx(configDir, store, log, null);
        Map<String, Object> parsed = ((OAuthProvider) p).exchange(ctx, json.stringify(reqBody));

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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    private static String stringOf(Object o) {
        return o instanceof String ? (String) o : null;
    }
}
