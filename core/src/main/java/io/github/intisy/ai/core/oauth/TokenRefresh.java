package io.github.intisy.ai.core.oauth;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.intisy.ai.core.manager.HttpFetcher;
import io.github.intisy.ai.core.store.Account;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Non-interactive OAuth token refresh. Java port of {@code libs/core-auth/src/oauth.ts}
 * ({@code accessTokenExpired} + {@code refreshAccessToken}). {@code now} is always passed in
 * explicitly (never read from the wall clock here) so callers/tests stay deterministic.
 */
public final class TokenRefresh {
    /** Matches JS {@code ACCESS_TOKEN_EXPIRY_BUFFER_MS = 60 * 1000}. */
    private static final long ACCESS_TOKEN_EXPIRY_BUFFER_MS = 60_000L;

    private TokenRefresh() {
    }

    /**
     * Expired or missing, with a buffer for clock skew. Matches JS: {@code !auth.access} or
     * {@code typeof auth.expires !== "number"} short-circuits to "expired", else
     * {@code auth.expires <= now + BUFFER}.
     */
    public static boolean accessTokenExpired(Account a, long now) {
        if (a == null || a.access == null || a.expires == null) return true;
        return a.expires <= now + ACCESS_TOKEN_EXPIRY_BUFFER_MS;
    }

    /**
     * POSTs {@code grant_type=refresh_token} (+ refresh_token, client_id, optional
     * client_secret/extraParams) form-urlencoded to {@code cfg.tokenUrl} via {@code http}.
     * Returns the new {access, expires, refresh} on success; throws {@link TokenRefreshError}
     * on a non-2xx response ({@code revoked=true} iff the token endpoint reported
     * {@code error=invalid_grant}).
     */
    public static Refreshed refresh(String refreshToken, OAuthConfig cfg, HttpFetcher http, long now) {
        if (refreshToken == null) return null;

        Map<String, String> params = new LinkedHashMap<>();
        params.put("grant_type", "refresh_token");
        params.put("refresh_token", refreshToken);
        params.put("client_id", cfg.clientId);
        if (cfg.clientSecret != null) params.put("client_secret", cfg.clientSecret);
        if (cfg.extraParams != null) params.putAll(cfg.extraParams);

        HttpFetcher.Resp response;
        try {
            response = http.post(cfg.tokenUrl, params);
        } catch (Exception e) {
            throw new TokenRefreshError("OAuth token refresh request failed: " + e.getMessage(), e);
        }

        if (response.status < 200 || response.status >= 300) {
            OAuthError parsed = parseOAuthError(response.body);
            boolean revoked = "invalid_grant".equals(parsed.code);
            String details = joinNonNull(parsed.code, parsed.description != null ? parsed.description : response.body);
            String base = "OAuth token refresh failed (" + response.status + ")";
            String message = details != null ? base + " - " + details : base;
            throw new TokenRefreshError(message, revoked);
        }

        JsonObject payload;
        try {
            payload = JsonParser.parseString(response.body == null ? "" : response.body).getAsJsonObject();
        } catch (Exception e) {
            throw new TokenRefreshError("OAuth token refresh returned an unparseable body: " + response.body, e);
        }

        String access = stringField(payload, "access_token");
        Double expiresIn = numberField(payload, "expires_in");
        String refresh = stringField(payload, "refresh_token");

        return new Refreshed(access, calculateTokenExpiry(now, expiresIn), refresh != null ? refresh : refreshToken);
    }

    /** Matches JS {@code calculateTokenExpiry}: defaults to 3600s; a non-positive value collapses to {@code requestTimeMs}. */
    private static long calculateTokenExpiry(long requestTimeMs, Double expiresInSeconds) {
        double seconds = expiresInSeconds != null ? expiresInSeconds : 3600;
        if (Double.isNaN(seconds) || seconds <= 0) return requestTimeMs;
        return requestTimeMs + (long) (seconds * 1000);
    }

    private static String stringField(JsonObject obj, String field) {
        JsonElement el = obj.get(field);
        return el != null && !el.isJsonNull() ? el.getAsString() : null;
    }

    /** Matches JS {@code typeof x === "number" ? x : default}: a non-numeric value (e.g. a string) falls back to {@code null} instead of throwing. */
    private static Double numberField(JsonObject obj, String field) {
        JsonElement el = obj.get(field);
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) return null;
        return el.getAsDouble();
    }

    private static String joinNonNull(String a, String b) {
        if (a == null) return b;
        if (b == null) return a;
        return a + ": " + b;
    }

    private static final class OAuthError {
        final String code;
        final String description;

        OAuthError(String code, String description) {
            this.code = code;
            this.description = description;
        }
    }

    /** Best-effort port of JS {@code parseOAuthError}: tolerates the varied error-body shapes OAuth endpoints return. */
    private static OAuthError parseOAuthError(String text) {
        if (text == null || text.isEmpty()) return new OAuthError(null, null);
        try {
            JsonElement el = JsonParser.parseString(text);
            if (!el.isJsonObject()) return new OAuthError(null, text);
            JsonObject payload = el.getAsJsonObject();
            JsonElement errorEl = payload.get("error");
            String code = null;
            String description = null;
            if (errorEl != null && errorEl.isJsonPrimitive()) {
                code = errorEl.getAsString();
            } else if (errorEl != null && errorEl.isJsonObject()) {
                JsonObject errObj = errorEl.getAsJsonObject();
                code = stringField(errObj, "status");
                if (code == null) code = stringField(errObj, "code");
                if (payload.get("error_description") == null) {
                    String msg = stringField(errObj, "message");
                    if (msg != null) return new OAuthError(code, msg);
                }
            }
            String errorDescription = stringField(payload, "error_description");
            if (errorDescription != null) return new OAuthError(code, errorDescription);
            return new OAuthError(code, description);
        } catch (Exception e) {
            return new OAuthError(null, text);
        }
    }
}
