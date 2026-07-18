package io.github.intisy.ai.exampleserver.discovery;

import io.github.intisy.ai.shared.spi.JsonCodec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Resolves the effective GitHub token used to authenticate {@link GithubOrgScan}, and manages the
 * console-driven connection state behind it: a manually pasted token, the {@code GITHUB_TOKEN}/
 * {@code GH_TOKEN} env var, or a token auto-detected from the locally installed {@code gh} CLI.
 *
 * <p>Token precedence for {@link #token()}, most specific first: manual &gt; env &gt; gh.
 *
 * <p>SECURITY: the token is a secret and is treated as such everywhere in this class -- it is
 * never logged (no {@code System.out}/{@code err}/logger of the value, including on a failed
 * detect/validate), never returned from any method other than {@link #token()} (whose only
 * legitimate caller is the {@code Authorization} header builder), and never written to disk or a
 * {@code Store}. The manual token lives only in memory for the life of this instance (session-
 * scoped); the gh-detected token is likewise cached in memory only -- {@code gh} itself owns its
 * own persisted credential, this class never touches it; the env token is whatever the OS process
 * environment already holds.
 */
public final class GithubAuth {

    private static final int GH_TIMEOUT_SECONDS = 5;
    private static final String USER_URL = "https://api.github.com/user";
    private static final String USER_AGENT = "ai-java-example-server";
    private static final int TIMEOUT_MS = 5000;

    /** HTTP seam mirroring {@link GithubOrgScan.Http}: {@code bearerToken} is never null here since
     *  callers only invoke it after confirming {@link #token()} is non-null. */
    interface Http {
        String get(String url, String bearerToken) throws IOException;
    }

    private final JsonCodec json;
    private final Supplier<String> envTokenSupplier;
    private final Supplier<String> ghCliRunner;
    private final Http http;

    /** In-memory only, session-scoped: set via {@link #setManualToken}, never persisted. */
    private volatile String manualToken;
    /** In-memory only: the last token {@link #detectFromGhCli} read from {@code gh auth token}. */
    private volatile String ghToken;

    public GithubAuth(JsonCodec json) {
        this(json, GithubAuth::envToken, GithubAuth::realGhAuthToken, GithubAuth::realHttpGet);
    }

    /** {@code envTokenSupplier} is a seam purely for testing the manual/env/gh precedence without
     *  depending on (or mutating) the real process environment. */
    GithubAuth(JsonCodec json, Supplier<String> envTokenSupplier, Supplier<String> ghCliRunner, Http http) {
        this.json = json;
        this.envTokenSupplier = envTokenSupplier;
        this.ghCliRunner = ghCliRunner;
        this.http = http;
        // Best-effort auto-detect at startup, only when no env token is already configured, so a
        // user already logged into `gh` is authenticated automatically without any action. Never
        // throws (detectFromGhCli itself never throws).
        if (!isSet(envTokenSupplier.get())) {
            detectFromGhCli();
        }
    }

    /** The current effective token per the manual &gt; env &gt; gh precedence, or {@code null} if
     *  none of the three tiers currently supplies one. */
    public String token() {
        String manual = manualToken;
        if (isSet(manual)) return manual;
        String env = envTokenSupplier.get();
        if (isSet(env)) return env;
        String gh = ghToken;
        return isSet(gh) ? gh : null;
    }

    /** Which precedence tier is currently supplying {@link #token()}: {@code "manual"}, {@code
     *  "env"}, {@code "gh"}, or {@code "none"}. */
    public String source() {
        if (isSet(manualToken)) return "manual";
        if (isSet(envTokenSupplier.get())) return "env";
        if (isSet(ghToken)) return "gh";
        return "none";
    }

    /** Sets/replaces the in-memory manual token. A null/blank value clears it, falling back to the
     *  env/gh tiers. Never written to disk. */
    public void setManualToken(String t) {
        manualToken = isSet(t) ? t.trim() : null;
    }

    /**
     * Runs {@code gh auth token} and, on success, caches its stdout (trimmed) as the gh-detected
     * token. Handles {@code gh} missing or the process erroring/timing out gracefully: returns
     * {@code false}, never throws, never logs the attempted output. Returns {@code true} only when
     * the command produced a non-empty token.
     */
    public boolean detectFromGhCli() {
        String out = ghCliRunner.get();
        if (!isSet(out)) {
            ghToken = null;
            return false;
        }
        ghToken = out.trim();
        return true;
    }

    /**
     * Calls {@code GET https://api.github.com/user} with the current token as a Bearer credential
     * and returns the {@code login} field -- this both validates the token and yields the username
     * for status display. Returns {@code null} when there is no token, on a 401/error response, or
     * on any network/parse failure. Never logs the token or the raw response body.
     */
    public String validateLogin() {
        String t = token();
        if (t == null) return null;
        try {
            Object parsed = json.parse(http.get(USER_URL, t));
            if (parsed instanceof Map) {
                Object login = ((Map<?, ?>) parsed).get("login");
                if (login instanceof String) return (String) login;
            }
        } catch (RuntimeException | IOException e) {
            // Deliberately not logging e.getMessage(): some JDKs/servers echo request details
            // (including the Authorization header) into connection-error messages.
            return null;
        }
        return null;
    }

    private static boolean isSet(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String envToken() {
        String t = System.getenv("GITHUB_TOKEN");
        if (t == null || t.trim().isEmpty()) t = System.getenv("GH_TOKEN");
        return t;
    }

    /** Real {@code gh auth token} invocation, bounded by {@link #GH_TIMEOUT_SECONDS} so a hung or
     *  missing {@code gh} can never block server startup/a detect request. */
    private static String realGhAuthToken() {
        Process proc = null;
        try {
            proc = new ProcessBuilder("gh", "auth", "token").start();
            boolean finished = proc.waitFor(GH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                return null;
            }
            if (proc.exitValue() != 0) {
                return null;
            }
            return readAll(proc.getInputStream());
        } catch (IOException e) {
            return null; // gh not installed / not on PATH -- expected, not an error condition.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (proc != null) proc.destroyForcibly();
        }
    }

    private static String realHttpGet(String url, String bearerToken) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Authorization", "Bearer " + bearerToken);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);

            int status = connection.getResponseCode();
            InputStream stream = status < 400 ? connection.getInputStream() : connection.getErrorStream();
            if (stream == null) return "";
            return readAll(stream);
        } finally {
            connection.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toString("UTF-8");
    }
}
