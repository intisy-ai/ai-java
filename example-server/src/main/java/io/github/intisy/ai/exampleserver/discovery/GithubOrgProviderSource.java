package io.github.intisy.ai.exampleserver.discovery;

import io.github.intisy.ai.shared.spi.JsonCodec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Lists and downloads provider-jar release assets from every repo in the {@code intisy-ai} GitHub
 * org, so the example server can offer them for on-demand install without a manual jar drop.
 * Delegates the actual scanning/caching/auth to the shared {@link GithubOrgScan}, filtering to
 * {@code kind == "provider"}. {@link #list()} is entirely best-effort: every network/parse failure
 * is caught and logged inside the scan (never thrown), yielding an empty list rather than blocking
 * whatever's calling it.
 */
public final class GithubOrgProviderSource implements ProviderSource {

    private final GithubOrgScan scan;

    public GithubOrgProviderSource(JsonCodec json) {
        this(new GithubOrgScan(json));
    }

    /** Shares one scan instance so the org is scanned once for both providers and proxies. */
    public GithubOrgProviderSource(GithubOrgScan scan) {
        this.scan = scan;
    }

    @Override
    public List<Entry> list() {
        List<Entry> entries = new ArrayList<>();
        for (GithubOrgScan.Asset asset : scan.scan()) {
            if ("provider".equals(asset.kind)) entries.add(toEntry(asset));
        }
        return entries;
    }

    @Override
    public Entry find(String name) {
        for (GithubOrgScan.Asset asset : scan.scanRepo(name)) {
            if ("provider".equals(asset.kind)) return toEntry(asset);
        }
        return null;
    }

    @Override
    public Path download(Entry entry, Path dir) throws IOException {
        return scan.download(new GithubOrgScan.Asset(entry.name, entry.assetName, entry.downloadUrl, "provider"), dir);
    }

    private static Entry toEntry(GithubOrgScan.Asset asset) {
        return new Entry(asset.repoName, asset.assetName, asset.downloadUrl);
    }
}
