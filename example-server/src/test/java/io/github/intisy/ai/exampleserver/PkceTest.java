package io.github.intisy.ai.exampleserver;

import io.github.intisy.ai.exampleserver.oauth.Pkce;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PkceTest {

    @Test
    void challengeMatchesRfc7636Vector() {
        // RFC 7636 Appendix B.
        String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        String expected = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
        assertEquals(expected, Pkce.challengeS256(verifier));
    }

    @Test
    void verifierIsUrlSafeUnpaddedAndUnique() {
        SecureRandom rng = new SecureRandom();
        String a = Pkce.verifier(rng);
        String b = Pkce.verifier(rng);
        assertNotEquals(a, b);
        assertFalse(a.contains("="), a);
        assertFalse(a.contains("+"), a);
        assertFalse(a.contains("/"), a);
        assertTrue(a.length() >= 43, a); // 32 bytes -> 43 base64url chars
    }

    @Test
    void stateIsNonEmptyAndUnique() {
        SecureRandom rng = new SecureRandom();
        assertNotEquals(Pkce.state(rng), Pkce.state(rng));
    }
}
