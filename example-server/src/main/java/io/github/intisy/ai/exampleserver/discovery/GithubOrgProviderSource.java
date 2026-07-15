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
import java.util.List;
import java.util.Map;

/**
 * Downloads provider-jar release assets from every repo in the {@code intisy-ai} GitHub org into a
 * local directory, so the example server can auto-populate its {@code providers/} folder without a
 * manual jar drop. Entirely best-effort: every network/parse failure is caught and logged (never
 * thrown) so a rate-limited or unreachable GitHub never blocks the server from starting with
 * whatever provider jars are already on disk.
 */
public final class GithubOrgProviderSource {

    private static final String LOG_PREFIX = "[example-server] ";
    private static final String ORG = "intisy-ai";
    private static final String REPOS_URL = "https://api.github.com/orgs/" + ORG + "/repos?per_page=100";
    private static final String USER_AGENT = "ai-java-example-server";
    private static final int TIMEOUT_MS = 5000;

    private final JsonCodec json;

    public GithubOrgProviderSource(JsonCodec json) {
        this.json = json;
    }

    /**
     * Lists every repo in the org, fetches each one's latest release, and downloads any asset that
     * looks like a provider jar into {@code dir} (skipping files that already exist there). Returns
     * the number of jars actually downloaded. Never throws — any failure along the way is logged to
     * {@code System.err} and simply yields fewer (possibly zero) downloads.
     */
    public int fetchInto(Path dir) {
        int downloaded = 0;
        try {
            Object reposJson = json.parse(httpGet(REPOS_URL));
            if (!(reposJson instanceof List)) {
                System.err.println(LOG_PREFIX + "org repo listing was not a JSON array; skipping org fetch");
                return 0;
            }
            for (Object repoObj : (List<?>) reposJson) {
                try {
                    if (!(repoObj instanceof Map)) continue;
                    Map<?, ?> repo = (Map<?, ?>) repoObj;
                    Object nameObj = repo.get("name");
                    if (!(nameObj instanceof String)) continue;
                    String name = (String) nameObj;
                    if (!looksLikeProviderRepo(repo, name)) continue;

                    downloaded += fetchReleaseAssets(name, dir);
                } catch (RuntimeException e) {
                    System.err.println(LOG_PREFIX + "skipping repo (unexpected error): " + e.getMessage());
                }
            }
        } catch (RuntimeException | IOException e) {
            System.err.println(LOG_PREFIX + "org provider fetch failed: " + e.getMessage());
        }
        return downloaded;
    }

    /** A repo counts as a provider source if its name or declared topics mention "provider". */
    private boolean looksLikeProviderRepo(Map<?, ?> repo, String name) {
        if (name.toLowerCase().contains("provider")) return true;
        Object topicsObj = repo.get("topics");
        if (topicsObj instanceof List) {
            for (Object topic : (List<?>) topicsObj) {
                if (topic instanceof String && ((String) topic).toLowerCase().contains("provider")) {
                    return true;
                }
            }
        }
        return false;
    }

    private int fetchReleaseAssets(String repoName, Path dir) {
        int downloaded = 0;
        try {
            String releaseUrl = "https://api.github.com/repos/" + ORG + "/" + repoName + "/releases/latest";
            Object releaseJson = json.parse(httpGet(releaseUrl));
            if (!(releaseJson instanceof Map)) return 0;
            Object assetsObj = ((Map<?, ?>) releaseJson).get("assets");
            if (!(assetsObj instanceof List)) return 0;

            for (Object assetObj : (List<?>) assetsObj) {
                if (!(assetObj instanceof Map)) continue;
                Map<?, ?> asset = (Map<?, ?>) assetObj;
                Object nameObj = asset.get("name");
                Object urlObj = asset.get("browser_download_url");
                if (!(nameObj instanceof String) || !(urlObj instanceof String)) continue;
                String assetName = (String) nameObj;
                if (!isProviderJarAsset(assetName)) continue;

                Path target = dir.resolve(assetName);
                if (Files.exists(target)) continue; // already have it

                try {
                    downloadTo((String) urlObj, target);
                    downloaded++;
                } catch (IOException e) {
                    System.err.println(LOG_PREFIX + "failed to download asset " + assetName + ": " + e.getMessage());
                }
            }
        } catch (RuntimeException | IOException e) {
            System.err.println(LOG_PREFIX + "failed to fetch release for " + repoName + ": " + e.getMessage());
        }
        return downloaded;
    }

    private static boolean isProviderJarAsset(String name) {
        return name.matches(".*-provider.*\\.jar") || name.endsWith("-standalone.jar");
    }

    private String httpGet(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", USER_AGENT);
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
