package io.github.intisy.ai.exampleserver.discovery;

import io.github.intisy.ai.shared.spi.JsonCodec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The single authenticated, TTL-cached scan of every release asset in the {@code intisy-ai}
 * GitHub org, shared by {@link GithubOrgProviderSource} and {@link GithubOrgProxySource} so the
 * org is only ever scanned once for both kinds of installable jar.
 *
 * <p>Sends a {@code GITHUB_TOKEN}/{@code GH_TOKEN} bearer token when present -- an unauthenticated
 * scan is subject to GitHub's low anonymous rate limit, which previously caused {@link #scan()} to
 * come back empty under load and wipe out an already-good provider/proxy list. {@link #scan()}
 * therefore never lets a failed or empty fresh attempt overwrite a still-fresh good cache, and
 * never throws.
 */
public final class GithubOrgScan {

    private static final String LOG_PREFIX = "[example-server] ";
    private static final String ORG = "intisy-ai";
    private static final String REPOS_URL = "https://api.github.com/orgs/" + ORG + "/repos?per_page=100";
    private static final String USER_AGENT = "ai-java-example-server";
    private static final int TIMEOUT_MS = 5000;
    private static final long CACHE_TTL_MS = 60_000L;

    /** One classified release asset: {@code kind} is {@code "provider"} or {@code "proxy"}.
     *  {@code version} is the release's {@code tag_name} with a leading {@code v} stripped (e.g.
     *  {@code "v1.5.1"} -> {@code "1.5.1"}), or {@code null} when the release carried no
     *  {@code tag_name}. */
    public static final class Asset {
        public final String repoName;
        public final String assetName;
        public final String downloadUrl;
        public final String kind;
        public final String version;

        public Asset(String repoName, String assetName, String downloadUrl, String kind, String version) {
            this.repoName = repoName;
            this.assetName = assetName;
            this.downloadUrl = downloadUrl;
            this.kind = kind;
            this.version = version;
        }

        @Override
        public String toString() {
            return "Asset{" + repoName + "/" + assetName + ", kind=" + kind + ", version=" + version + "}";
        }
    }


    /** HTTP seam: {@code bearerToken} is null/empty when no token is configured. */
    interface Http {
        String get(String url, String bearerToken) throws IOException;
    }

    private final JsonCodec json;
    private final Http http;
    private final LongSupplier clock;
    private final Supplier<String> token;

    private volatile List<Asset> cached;
    private volatile long cachedAt = Long.MIN_VALUE;

    public GithubOrgScan(JsonCodec json) {
        this(json, GithubOrgScan::realHttpGet, System::currentTimeMillis, GithubOrgScan::envToken);
    }

    /**
     * Authenticates the scan with {@code tokenSupplier} (e.g. {@code GithubAuth::token}) instead of
     * the plain env-var lookup -- read fresh at the start of every scan/find (see {@link
     * #httpGet}), so connecting/changing a token from the console takes effect on the very next
     * scan, no restart required.
     */
    public GithubOrgScan(JsonCodec json, Supplier<String> tokenSupplier) {
        this(json, GithubOrgScan::realHttpGet, System::currentTimeMillis, tokenSupplier);
    }

    GithubOrgScan(JsonCodec json, Http http, LongSupplier clock, Supplier<String> token) {
        this.json = json;
        this.http = http;
        this.clock = clock;
        this.token = token;
    }

    private static String envToken() {
        String t = System.getenv("GITHUB_TOKEN");
        if (t == null || t.trim().isEmpty()) t = System.getenv("GH_TOKEN");
        return t;
    }

    /**
     * The cached, org-wide asset list. Never throws: a network/parse failure returns the last
     * good cache while it's still fresh, else an empty list. A fresh scan that comes back empty
     * is treated the same as a failure -- it does NOT overwrite a still-fresh good cache, since an
     * empty result is far more often a transient rate-limit/network hiccup than an org that
     * genuinely dropped every asset.
     */
    public List<Asset> scan() {
        long now = clock.getAsLong();
        List<Asset> snapshot = cached;
        if (snapshot != null && now - cachedAt < CACHE_TTL_MS) return snapshot;
        try {
            List<Asset> fresh = doScan();
            if (!fresh.isEmpty() || snapshot == null) {
                cached = fresh;
                cachedAt = now;
                return fresh;
            }
            return snapshot;
        } catch (RuntimeException e) {
            System.err.println(LOG_PREFIX + "org scan failed: " + e.getMessage());
            return snapshot != null ? snapshot : Collections.emptyList();
        }
    }

    /**
     * Clears the TTL cache so the very next {@link #scan()} re-fetches immediately instead of
     * serving a stale cached result -- called after the effective token changes (connect/disconnect
     * from the console) so an authenticated re-scan isn't stuck waiting out the TTL.
     */
    public void invalidateCache() {
        cached = null;
        cachedAt = Long.MIN_VALUE;
    }

    /**
     * Targeted, uncached lookup of a single repo's latest release -- backs {@code find(name)} on
     * both sources so an install still works even when the cached org-wide scan is empty or
     * stale. Never throws; yields an empty list on any failure.
     */
    public List<Asset> scanRepo(String repoName) {
        try {
            return listReleaseAssets(repoName);
        } catch (RuntimeException e) {
            System.err.println(LOG_PREFIX + "targeted scan of " + repoName + " failed: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Downloads {@code asset}'s jar into {@code dir}, replacing any existing file of the same
     * name. Resolves using only the file-name component of {@code asset.assetName} so a
     * maliciously-named asset (e.g. {@code ../evil.jar}) can't escape {@code dir}. Also records
     * {@code asset.version} in a {@code <jar>.version} sidecar file next to it (see {@link
     * InstalledVersions}) -- a no-op when the release carried no {@code tag_name} (version null).
     */
    public Path download(Asset asset, Path dir) throws IOException {
        Path target = dir.resolve(java.nio.file.Paths.get(asset.assetName).getFileName().toString());
        downloadTo(asset.downloadUrl, target);
        InstalledVersions.write(target, asset.version);
        return target;
    }

    private List<Asset> doScan() {
        List<Asset> assets = new ArrayList<>();
        try {
            Object reposJson = json.parse(httpGet(REPOS_URL));
            if (!(reposJson instanceof List)) {
                System.err.println(LOG_PREFIX + "org repo listing was not a JSON array; skipping org fetch");
                return assets;
            }
            for (Object repoObj : (List<?>) reposJson) {
                try {
                    if (!(repoObj instanceof Map)) continue;
                    Object nameObj = ((Map<?, ?>) repoObj).get("name");
                    if (!(nameObj instanceof String)) continue;
                    assets.addAll(listReleaseAssets((String) nameObj));
                } catch (RuntimeException e) {
                    System.err.println(LOG_PREFIX + "skipping repo (unexpected error): " + e.getMessage());
                }
            }
        } catch (RuntimeException | IOException e) {
            System.err.println(LOG_PREFIX + "org scan failed: " + e.getMessage());
        }
        return assets;
    }

    private List<Asset> listReleaseAssets(String repoName) {
        List<Asset> assets = new ArrayList<>();
        try {
            String releaseUrl = "https://api.github.com/repos/" + ORG + "/" + repoName + "/releases/latest";
            Object releaseJson = json.parse(httpGet(releaseUrl));
            if (!(releaseJson instanceof Map)) return assets;
            Map<?, ?> release = (Map<?, ?>) releaseJson;
            Object tagObj = release.get("tag_name");
            String version = tagObj instanceof String ? InstalledVersions.normalize((String) tagObj) : null;
            Object assetsObj = release.get("assets");
            if (!(assetsObj instanceof List)) return assets;

            for (Object assetObj : (List<?>) assetsObj) {
                if (!(assetObj instanceof Map)) continue;
                Map<?, ?> asset = (Map<?, ?>) assetObj;
                Object nameObj = asset.get("name");
                Object urlObj = asset.get("browser_download_url");
                if (!(nameObj instanceof String) || !(urlObj instanceof String)) continue;
                String assetName = (String) nameObj;
                String kind = classify(assetName);
                if (kind == null) continue;

                assets.add(new Asset(repoName, assetName, (String) urlObj, kind, version));
            }
        } catch (RuntimeException | IOException e) {
            System.err.println(LOG_PREFIX + "failed to list release for " + repoName + ": " + e.getMessage());
        }
        return assets;
    }

    /** The provider/proxy asset-name regexes are disjoint -- an asset can't match both -- so
     *  classification is unambiguous. Anything else is skipped (not an installable jar). */
    private static String classify(String name) {
        if (name.matches(".*-provider.*\\.jar")) return "provider";
        if (name.matches(".*-proxy.*\\.jar")) return "proxy";
        return null;
    }

    private String httpGet(String url) throws IOException {
        return http.get(url, token.get());
    }

    private static String realHttpGet(String url, String bearerToken) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            if (bearerToken != null && !bearerToken.trim().isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + bearerToken);
            }
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

    private void downloadTo(String url, Path target) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            // Deliberately NO Authorization header here: a release asset's browser_download_url
            // 302-redirects to a presigned objects.githubusercontent.com/S3 URL that rejects a
            // request also carrying a bearer token ("only one auth mechanism allowed"), and some
            // JDKs forward request headers across the redirect. Public release assets need no auth
            // to download -- the token is only needed for the API scan calls in httpGet.
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);

            try (InputStream in = connection.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
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
