package io.github.intisy.ai.shared.oauth;

import io.github.intisy.ai.shared.model.Account;
import io.github.intisy.ai.shared.spi.HttpClient;
import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.http.HttpRequest;
import io.github.intisy.ai.shared.spi.http.HttpResponse;

import java.io.UnsupportedEncodingException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Non-interactive OAuth token refresh. Java port of {@code libs/core-auth/src/oauth.ts}
 * ({@code accessTokenExpired} + {@code refreshAccessToken}), rewired onto the {@link HttpClient}
 * + {@link JsonCodec} SPIs (no {@code HttpURLConnection}/gson) so it stays transpilable.
 * {@code now} is always passed in explicitly (never read from the wall clock here) so
 * callers/tests stay deterministic.
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
    public static Refreshed refresh(String refreshToken, OAuthConfig cfg, HttpClient http, JsonCodec json, long now) {
        if (refreshToken == null) return null;

        Map<String, String> params = new LinkedHashMap<>();
        params.put("grant_type", "refresh_token");
        params.put("refresh_token", refreshToken);
        params.put("client_id", cfg.clientId);
        if (cfg.clientSecret != null) params.put("client_secret", cfg.clientSecret);
        if (cfg.extraParams != null) params.putAll(cfg.extraParams);

        HttpRequest request = new HttpRequest();
        request.method = "POST";
        request.url = cfg.tokenUrl;
        request.headers = new LinkedHashMap<>();
        request.headers.put("content-type", "application/x-www-form-urlencoded");
        request.body = formEncode(params);

        HttpResponse response;
        try {
            response = http.send(request);
        } catch (Exception e) {
            throw new TokenRefreshError("OAuth token refresh request failed: " + e.getMessage(), e);
        }

        if (response.status < 200 || response.status >= 300) {
            OAuthError parsed = parseOAuthError(response.body, json);
            boolean revoked = "invalid_grant".equals(parsed.code);
            String details = joinNonNull(parsed.code, parsed.description != null ? parsed.description : response.body);
            String base = "OAuth token refresh failed (" + response.status + ")";
            String message = details != null ? base + " - " + details : base;
            throw new TokenRefreshError(message, revoked);
        }

        Map<String, Object> payload;
        try {
            Map<String, Object> parsed = asMap(json.parse(response.body == null ? "" : response.body));
            if (parsed == null) throw new IllegalArgumentException("response body is not a JSON object");
            payload = parsed;
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

    /** application/x-www-form-urlencoded body. No {@code java.net.URLEncoder} (transpilability). */
    private static String formEncode(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) sb.append('&');
            first = false;
            sb.append(percentEncode(e.getKey()));
            sb.append('=');
            sb.append(percentEncode(e.getValue()));
        }
        return sb.toString();
    }

    /** RFC 3986 unreserved chars pass through; space becomes {@code '+'}; everything else is percent-escaped UTF-8 bytes. */
    private static String percentEncode(String s) {
        if (s == null) return "";
        byte[] bytes;
        try {
            bytes = s.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e); // UTF-8 is always supported
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            int c = b & 0xFF;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '*') {
                sb.append((char) c);
            } else if (c == ' ') {
                sb.append('+');
            } else {
                sb.append('%');
                String hex = Integer.toHexString(c).toUpperCase();
                if (hex.length() < 2) sb.append('0');
                sb.append(hex);
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    private static String stringField(Map<String, Object> obj, String field) {
        Object v = obj.get(field);
        return v instanceof String ? (String) v : null;
    }

    /** Matches JS {@code typeof x === "number" ? x : default}: a non-numeric value (e.g. a string) falls back to {@code null} instead of throwing. */
    private static Double numberField(Map<String, Object> obj, String field) {
        Object v = obj.get(field);
        return v instanceof Number ? ((Number) v).doubleValue() : null;
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
    private static OAuthError parseOAuthError(String text, JsonCodec json) {
        if (text == null || text.isEmpty()) return new OAuthError(null, null);
        try {
            Map<String, Object> payload = asMap(json.parse(text));
            if (payload == null) return new OAuthError(null, text);
            Object errorEl = payload.get("error");
            String code = null;
            String description = null;
            if (errorEl instanceof String) {
                code = (String) errorEl;
            } else if (errorEl instanceof Map) {
                Map<String, Object> errObj = asMap(errorEl);
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
