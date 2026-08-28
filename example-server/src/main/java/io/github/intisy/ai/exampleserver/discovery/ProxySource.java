package io.github.intisy.ai.exampleserver.discovery;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * A source of installable proxy jars, decoupled from any particular hosting scheme (GitHub
 * releases today; could be a private registry or a local index tomorrow). {@link #list()} is a
 * pure lookup (it never writes to disk), so callers (e.g. an install API) can show what's
 * available before committing to a download. Proxy-side mirror of {@code
 * ProviderSource}.
 */
public interface ProxySource {

    /**
     * {@return every installable proxy, discovered fresh on every call; never
     * {@code null}, and empty when none are available or the source is unreachable}
     */
    List<Entry> list();

    /** Targeted lookup of a single installable proxy by its {@code name} (repo name), scanning
     *  only that one repo's latest release, so an install works even when the full org scan is
     *  rate-limited or cached-empty.
     *
     * @param name the repo name to look up
     * @return that proxy, or {@code null} when it was not found
     */
    Entry find(String name);

    /**
     * Downloads one proxy's jar.
     *
     * @param entry the proxy to download
     * @param dir where the jar is written
     * @return the path written
     * @throws IOException when the download or the write fails
     */
    Path download(Entry entry, Path dir) throws IOException;

    /** One installable proxy jar: a human-readable {@code name}, the {@code assetName} it will
     *  be saved as, and the {@code downloadUrl} to fetch it from. */
    final class Entry {
        /** The repo name, which is what a person sees and what {@code find} looks up. */
        public final String name;
        /** The file name the jar is saved as. */
        public final String assetName;
        /** Where the jar is fetched from. */
        public final String downloadUrl;

        /**
         * @param name the repo name
         * @param assetName the file name the jar is saved as
         * @param downloadUrl where the jar is fetched from
         */
        public Entry(String name, String assetName, String downloadUrl) {
            this.name = name;
            this.assetName = assetName;
            this.downloadUrl = downloadUrl;
        }
    }
}
