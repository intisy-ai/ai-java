package io.github.intisy.ai.exampleserver.discovery;

import io.github.intisy.ai.shared.spi.JsonCodec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link GithubOrgScan} entirely through its injectable {@code Http} + clock seams --
 * no network. Covers the exact bug chain that motivated this class: an unauthenticated org scan
 * getting rate-limited to an empty result must never wipe out a previously-good cache, and the
 * scan must actually send the {@code Authorization} header when a token is configured.
 */
class GithubOrgScanTest {

    private static final String REPOS_URL = "https://api.github.com/orgs/intisy-ai/repos?per_page=100";

    private static String releaseUrl(String repo) {
        return "https://api.github.com/repos/intisy-ai/" + repo + "/releases/latest";
    }

    private static String reposJson(String... repoNames) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < repoNames.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"name\":\"").append(repoNames[i]).append("\"}");
        }
        return sb.append("]").toString();
    }

    private static String releaseJson(String... assetNames) {
        StringBuilder sb = new StringBuilder("{\"assets\":[");
        for (int i = 0; i < assetNames.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"name\":\"").append(assetNames[i])
                    .append("\",\"browser_download_url\":\"https://example.invalid/")
                    .append(assetNames[i]).append("\"}");
        }
        return sb.append("]}").toString();
    }

    /** A trivial {@link JsonCodec} good enough for the tiny fixed shapes this test emits: a repo
     *  list ({@code [{"name":..}]}) and a release ({@code {"assets":[{"name":..,
     *  "browser_download_url":..}]}}). Avoids pulling in a real codec implementation just for
     *  fixture parsing. */
    private static final class FakeJsonCodec implements JsonCodec {
        @Override
        public Object parse(String json) {
            if (json.trim().startsWith("[")) return parseRepoList(json);
            Map<String, Object> release = new HashMap<>();
            release.put("assets", parseAssetList(json));
            return release;
        }

        private List<Object> parseRepoList(String json) {
            List<Object> repos = new ArrayList<>();
            for (String name : extractField(json, "name")) {
                Map<String, Object> repo = new HashMap<>();
                repo.put("name", name);
                repos.add(repo);
            }
            return repos;
        }

        private List<Object> parseAssetList(String json) {
            List<String> names = extractField(json, "name");
            List<String> urls = extractField(json, "browser_download_url");
            List<Object> assets = new ArrayList<>();
            for (int i = 0; i < names.size(); i++) {
                Map<String, Object> asset = new HashMap<>();
                asset.put("name", names.get(i));
                asset.put("browser_download_url", urls.get(i));
                assets.add(asset);
            }
            return assets;
        }

        private List<String> extractField(String json, String field) {
            List<String> values = new ArrayList<>();
            String needle = "\"" + field + "\":\"";
            int idx = 0;
            while ((idx = json.indexOf(needle, idx)) != -1) {
                int start = idx + needle.length();
                int end = json.indexOf('"', start);
                values.add(json.substring(start, end));
                idx = end;
            }
            return values;
        }

        @Override
        public String stringify(Object value) {
            throw new UnsupportedOperationException("not needed by this test");
        }
    }

    /** A scripted {@link GithubOrgScan.Http} that serves fixed responses keyed by URL and counts
     *  calls, so tests can assert cache hits/misses without any network. */
    private static final class FakeHttp implements GithubOrgScan.Http {
        final Map<String, String> responses = new HashMap<>();
        final AtomicInteger callCount = new AtomicInteger();
        final List<String> tokensSeen = new ArrayList<>();
        volatile RuntimeException failure;

        @Override
        public String get(String url, String bearerToken) {
            callCount.incrementAndGet();
            tokensSeen.add(bearerToken);
            if (failure != null) throw failure;
            String body = responses.get(url);
            if (body == null) throw new IllegalStateException("no fixture for " + url);
            return body;
        }
    }

    private GithubOrgScan newScan(FakeHttp http, AtomicLong clock, String token) {
        return new GithubOrgScan(new FakeJsonCodec(), http, clock::get, () -> token);
    }

    @Test
    void authHeaderCarriesConfiguredToken() {
        FakeHttp http = new FakeHttp();
        http.responses.put(REPOS_URL, reposJson("claude-code-proxy"));
        http.responses.put(releaseUrl("claude-code-proxy"), releaseJson("claude-code-proxy.jar"));
        AtomicLong clock = new AtomicLong(0);

        GithubOrgScan scan = newScan(http, clock, "secret-token-123");
        scan.scan();

        assertTrue(http.tokensSeen.contains("secret-token-123"),
                "the Http seam must receive the token so the real impl can send it as the bearer");
    }

    @Test
    void classifiesProviderAndProxyAssetsAndSkipsOthers() {
        FakeHttp http = new FakeHttp();
        http.responses.put(REPOS_URL, reposJson("claude-code-auth", "claude-code-proxy"));
        http.responses.put(releaseUrl("claude-code-auth"), releaseJson("claude-code-auth-provider.jar", "README.md"));
        http.responses.put(releaseUrl("claude-code-proxy"), releaseJson("claude-code-proxy-proxy.jar"));
        AtomicLong clock = new AtomicLong(0);

        List<GithubOrgScan.Asset> assets = newScan(http, clock, null).scan();

        assertEquals(2, assets.size(), assets.toString());
        GithubOrgScan.Asset provider = assets.stream()
                .filter(a -> a.assetName.equals("claude-code-auth-provider.jar")).findFirst().orElse(null);
        assertNotNull(provider);
        assertEquals("provider", provider.kind);
        assertEquals("claude-code-auth", provider.repoName);

        GithubOrgScan.Asset proxy = assets.stream()
                .filter(a -> a.assetName.equals("claude-code-proxy-proxy.jar")).findFirst().orElse(null);
        assertNotNull(proxy);
        assertEquals("proxy", proxy.kind);
    }

    @Test
    void secondScanWithinTtlIsACacheHit() {
        FakeHttp http = new FakeHttp();
        http.responses.put(REPOS_URL, reposJson("claude-code-auth"));
        http.responses.put(releaseUrl("claude-code-auth"), releaseJson("claude-code-auth-provider.jar"));
        AtomicLong clock = new AtomicLong(0);
        GithubOrgScan scan = newScan(http, clock, null);

        scan.scan();
        int callsAfterFirst = http.callCount.get();
        clock.addAndGet(1_000L); // well within the 60s TTL
        scan.scan();

        assertEquals(callsAfterFirst, http.callCount.get(), "cached scan must not call Http again");
    }

    @Test
    void scanAfterTtlExpiryReCallsHttp() {
        FakeHttp http = new FakeHttp();
        http.responses.put(REPOS_URL, reposJson("claude-code-auth"));
        http.responses.put(releaseUrl("claude-code-auth"), releaseJson("claude-code-auth-provider.jar"));
        AtomicLong clock = new AtomicLong(0);
        GithubOrgScan scan = newScan(http, clock, null);

        scan.scan();
        int callsAfterFirst = http.callCount.get();
        clock.addAndGet(60_001L); // just past the 60s TTL
        scan.scan();

        assertTrue(http.callCount.get() > callsAfterFirst, "expired cache must trigger a re-scan");
    }

    @Test
    void failingHttpReturnsCachedResultIfFreshElseEmptyAndNeverThrows() {
        FakeHttp http = new FakeHttp();
        http.responses.put(REPOS_URL, reposJson("claude-code-auth"));
        http.responses.put(releaseUrl("claude-code-auth"), releaseJson("claude-code-auth-provider.jar"));
        AtomicLong clock = new AtomicLong(0);
        GithubOrgScan scan = newScan(http, clock, null);

        List<GithubOrgScan.Asset> good = scan.scan();
        assertFalse(good.isEmpty());

        // Force the next scan (after TTL expiry, so it's not just a cache hit) to fail.
        clock.addAndGet(60_001L);
        http.failure = new RuntimeException("rate limited");
        List<GithubOrgScan.Asset> afterFailure = scan.scan();
        assertEquals(good.size(), afterFailure.size(),
                "a failed re-scan must fall back to the last good cache, not wipe it");

        // A scan that has NEVER succeeded must yield empty on failure, never throw.
        GithubOrgScan neverSucceeded = newScan(http, clock, null);
        List<GithubOrgScan.Asset> result = neverSucceeded.scan();
        assertTrue(result.isEmpty());
    }

    @Test
    void providerSourceListsOnlyProvidersAndProxySourceListsOnlyProxies() {
        FakeHttp http = new FakeHttp();
        http.responses.put(REPOS_URL, reposJson("claude-code-auth", "claude-code-proxy"));
        http.responses.put(releaseUrl("claude-code-auth"), releaseJson("claude-code-auth-provider.jar"));
        http.responses.put(releaseUrl("claude-code-proxy"), releaseJson("claude-code-proxy-proxy.jar"));
        AtomicLong clock = new AtomicLong(0);
        GithubOrgScan scan = newScan(http, clock, null);

        ProviderSource providerSource = new GithubOrgProviderSource(scan);
        ProxySource proxySource = new GithubOrgProxySource(scan);

        List<ProviderSource.Entry> providers = providerSource.list();
        assertEquals(1, providers.size(), providers.toString());
        assertEquals("claude-code-auth-provider.jar", providers.get(0).assetName);

        List<ProxySource.Entry> proxies = proxySource.list();
        assertEquals(1, proxies.size(), proxies.toString());
        assertEquals("claude-code-proxy-proxy.jar", proxies.get(0).assetName);
    }
}
