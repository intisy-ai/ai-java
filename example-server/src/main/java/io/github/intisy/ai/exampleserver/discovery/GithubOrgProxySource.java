package io.github.intisy.ai.exampleserver.discovery;

import io.github.intisy.ai.shared.spi.JsonCodec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Lists and downloads proxy-jar release assets from every repo in the {@code intisy-ai} GitHub
 * org. Proxy-side mirror of {@link GithubOrgProviderSource}: delegates to the same shared {@link
 * GithubOrgScan}, filtering to {@code kind == "proxy"}, so the org is only ever scanned once for
 * both kinds of installable jar.
 */
public final class GithubOrgProxySource implements ProxySource {

    private final GithubOrgScan scan;

    public GithubOrgProxySource(JsonCodec json) {
        this(new GithubOrgScan(json));
    }

    /** Shares one scan instance so the org is scanned once for both providers and proxies. */
    public GithubOrgProxySource(GithubOrgScan scan) {
        this.scan = scan;
    }

    @Override
    public List<Entry> list() {
        List<Entry> entries = new ArrayList<>();
        for (GithubOrgScan.Asset asset : scan.scan()) {
            if ("proxy".equals(asset.kind)) entries.add(toEntry(asset));
        }
        return entries;
    }

    @Override
    public Entry find(String name) {
        for (GithubOrgScan.Asset asset : scan.scanRepo(name)) {
            if ("proxy".equals(asset.kind)) return toEntry(asset);
        }
        return null;
    }

    @Override
    public Path download(Entry entry, Path dir) throws IOException {
        return scan.download(new GithubOrgScan.Asset(entry.name, entry.assetName, entry.downloadUrl, "proxy"), dir);
    }

    private static Entry toEntry(GithubOrgScan.Asset asset) {
        return new Entry(asset.repoName, asset.assetName, asset.downloadUrl);
    }
}
