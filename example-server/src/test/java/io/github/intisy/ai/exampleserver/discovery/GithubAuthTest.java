package io.github.intisy.ai.exampleserver.discovery;

import io.github.intisy.ai.shared.spi.JsonCodec;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link GithubAuth} entirely through its injectable env/gh-cli/http seams -- no real
 * {@code gh} process and no dependence on the real OS environment (a plain {@code GITHUB_TOKEN}
 * happening to be set in the CI runner, e.g. GitHub Actions' own auto-injected token, must never
 * make these tests flaky). Also asserts the security contract: a manually-set token is never
 * echoed back by any accessor other than {@link GithubAuth#token()}.
 */
class GithubAuthTest {

    /** A trivial {@link JsonCodec} good enough for the tiny {@code {"login":".."}} /
     *  {@code {"message":".."}} shapes {@code /user} responses take here. */
    private static final class FakeJsonCodec implements JsonCodec {
        @Override
        public Object parse(String json) {
            Map<String, Object> result = new HashMap<>();
            String login = extractField(json, "login");
            if (login != null) result.put("login", login);
            return result;
        }

        private String extractField(String json, String field) {
            String needle = "\"" + field + "\":\"";
            int idx = json.indexOf(needle);
            if (idx == -1) return null;
            int start = idx + needle.length();
            int end = json.indexOf('"', start);
            return json.substring(start, end);
        }

        @Override
        public String stringify(Object value) {
            throw new UnsupportedOperationException("not needed by this test");
        }
    }

    private static Supplier<String> constantEnv(String value) {
        return () -> value;
    }

    private static Supplier<String> constantGh(String value) {
        return () -> value;
    }

    private GithubAuth newAuth(String env, String gh, GithubAuth.Http http) {
        return new GithubAuth(new FakeJsonCodec(), constantEnv(env), constantGh(gh), http);
    }

    private static GithubAuth.Http neverCalledHttp() {
        return (url, token) -> {
            throw new AssertionError("http must not be called by this test");
        };
    }

    // -- precedence --

    @Test
    void noTokenAnywhereYieldsNoneSourceAndNullToken() {
        GithubAuth auth = newAuth(null, null, neverCalledHttp());
        assertNull(auth.token());
        assertEquals("none", auth.source());
    }

    @Test
    void envSuppliesTokenWhenNoManualIsSet() {
        GithubAuth auth = newAuth("env-token-1", null, neverCalledHttp());
        assertEquals("env-token-1", auth.token());
        assertEquals("env", auth.source());
    }

    @Test
    void ghSuppliesTokenOnlyWhenNeitherManualNorEnvIsSet() {
        // Construction auto-detects from gh since env is blank at construction time.
        GithubAuth auth = newAuth(null, "gh-token-1", neverCalledHttp());
        assertEquals("gh-token-1", auth.token());
        assertEquals("gh", auth.source());
    }

    @Test
    void manualTokenOutranksBothEnvAndGh() {
        GithubAuth auth = newAuth("env-token", "gh-token", neverCalledHttp());
        auth.setManualToken("manual-token");
        assertEquals("manual-token", auth.token());
        assertEquals("manual", auth.source());
    }

    @Test
    void envOutranksGhEvenWhenGhWasAlreadyDetected() {
        // gh auto-detects at construction only when env is blank at THAT time; here env is already
        // set, so gh never even gets auto-detected -- env still must win over token()'s live gh
        // fallback regardless.
        GithubAuth auth = newAuth("env-token", "gh-token", neverCalledHttp());
        assertEquals("env", auth.source());
        assertEquals("env-token", auth.token());
    }

    @Test
    void setManualTokenNullClearsItAndFallsBackToEnv() {
        GithubAuth auth = newAuth("env-token", null, neverCalledHttp());
        auth.setManualToken("manual-token");
        assertEquals("manual", auth.source());

        auth.setManualToken(null);
        assertEquals("env", auth.source());
        assertEquals("env-token", auth.token());
    }

    @Test
    void setManualTokenBlankAlsoClearsIt() {
        GithubAuth auth = newAuth(null, null, neverCalledHttp());
        auth.setManualToken("   ");
        assertEquals("none", auth.source());
        assertNull(auth.token());
    }

    // -- detectFromGhCli, unit-testable via the injected gh-cli seam (no real `gh` process) --

    @Test
    void detectFromGhCliWithNonEmptyOutputConnects() {
        GithubAuth auth = newAuth(null, null, neverCalledHttp());
        assertEquals("none", auth.source()); // gh returned null at construction time

        GithubAuth withGh = new GithubAuth(new FakeJsonCodec(), constantEnv(null),
                constantGh("  detected-token  \n"), neverCalledHttp());
        assertTrue(withGh.detectFromGhCli());
        assertEquals("detected-token", withGh.token(), "stdout must be trimmed");
        assertEquals("gh", withGh.source());
    }

    @Test
    void detectFromGhCliWithEmptyOutputIsNotConnected() {
        GithubAuth auth = new GithubAuth(new FakeJsonCodec(), constantEnv(null), constantGh(""), neverCalledHttp());
        assertFalse(auth.detectFromGhCli());
        assertEquals("none", auth.source());
        assertNull(auth.token());
    }

    @Test
    void detectFromGhCliWithWhitespaceOnlyOutputIsNotConnected() {
        GithubAuth auth = new GithubAuth(new FakeJsonCodec(), constantEnv(null), constantGh("   \n  "), neverCalledHttp());
        assertFalse(auth.detectFromGhCli());
        assertEquals("none", auth.source());
    }

    @Test
    void detectFromGhCliWithNullOutputNeverThrows() {
        // Models "gh missing" / process failure: the real runner catches IOException internally and
        // returns null -- the seam here does the same thing directly.
        GithubAuth auth = new GithubAuth(new FakeJsonCodec(), constantEnv(null), constantGh(null), neverCalledHttp());
        assertFalse(auth.detectFromGhCli());
        assertEquals("none", auth.source());
        assertNull(auth.token());
    }

    // -- validateLogin --

    @Test
    void validateLoginReturnsNullWhenNoToken() {
        GithubAuth auth = newAuth(null, null, neverCalledHttp());
        assertNull(auth.validateLogin());
    }

    @Test
    void validateLoginReturnsLoginFieldFromTheUserEndpoint() {
        GithubAuth.Http http = (url, token) -> {
            assertEquals("https://api.github.com/user", url);
            assertEquals("env-token", token);
            return "{\"login\":\"octocat\"}";
        };
        GithubAuth auth = newAuth("env-token", null, http);
        assertEquals("octocat", auth.validateLogin());
    }

    @Test
    void validateLoginReturnsNullOnErrorResponseBody() {
        GithubAuth.Http http = (url, token) -> "{\"message\":\"Bad credentials\"}";
        GithubAuth auth = newAuth("bad-token", null, http);
        assertNull(auth.validateLogin());
    }

    @Test
    void validateLoginReturnsNullOnHttpFailureAndNeverThrows() {
        GithubAuth.Http http = (url, token) -> {
            throw new java.io.IOException("network down");
        };
        GithubAuth auth = newAuth("env-token", null, http);
        assertNull(auth.validateLogin());
    }

    // -- security: the token must never surface anywhere except token() --

    @Test
    void toStringNeverContainsTheToken() {
        GithubAuth auth = newAuth(null, null, neverCalledHttp());
        auth.setManualToken("super-secret-token-value");
        assertFalse(auth.toString().contains("super-secret-token-value"));
    }
}
