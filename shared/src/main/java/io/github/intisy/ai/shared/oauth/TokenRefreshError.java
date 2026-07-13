package io.github.intisy.ai.shared.oauth;

/**
 * Java analog of the JS {@code TokenRefreshError} (see {@code libs/core-auth/src/oauth.ts:22-32}).
 * {@code revoked} is {@code true} exactly when the token endpoint reported
 * {@code error=invalid_grant} — the refresh token itself was revoked/expired, so the account
 * needs re-auth rather than a retry.
 */
public class TokenRefreshError extends RuntimeException {
    public final boolean revoked;

    public TokenRefreshError(String message, boolean revoked) {
        super(message);
        this.revoked = revoked;
    }

    public TokenRefreshError(String message, Throwable cause) {
        super(message, cause);
        this.revoked = false;
    }
}
