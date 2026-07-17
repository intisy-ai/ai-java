package io.github.intisy.ai.exampleserver.oauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * PKCE (RFC 7636) + CSRF-state helpers for the server-side OAuth flow. JVM-only (uses
 * {@link SecureRandom}/{@link MessageDigest}) — it lives in the example-server, not in the
 * transpilable {@code :accounts} core, so it may use {@code java.security}/{@code java.util.Base64}.
 */
public final class Pkce {
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private Pkce() {
    }

    public static String verifier(SecureRandom rng) {
        byte[] bytes = new byte[32];
        rng.nextBytes(bytes);
        return URL.encodeToString(bytes);
    }

    public static String state(SecureRandom rng) {
        byte[] bytes = new byte[16];
        rng.nextBytes(bytes);
        return URL.encodeToString(bytes);
    }

    public static String challengeS256(String verifier) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha.digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return URL.encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // guaranteed present on any JVM
        }
    }
}
