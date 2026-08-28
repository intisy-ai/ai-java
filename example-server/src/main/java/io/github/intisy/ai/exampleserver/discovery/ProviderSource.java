package io.github.intisy.ai.exampleserver.discovery;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * A source of installable provider jars, decoupled from any particular hosting scheme (GitHub
 * releases today; could be a private registry or a local index tomorrow). {@link #list()} is a
 * pure lookup (it never writes to disk), so callers (e.g. an install API) can show what's
 * available before committing to a download.
 */
public interface ProviderSource {

    /**
     * {@return every installable provider, discovered fresh on every call; never
     * {@code null}, and empty when none are available or the source is unreachable}
     */
    List<Entry> list();

    /** Targeted lookup of a single installable provider by its {@code name} (repo name), scanning
     *  only that one repo's latest release -- so an install works even when the full org scan is
     *  rate-limited or cached-empty.
     *
     * @param name the repo name to look up
     * @return that provider, or {@code null} when it was not found
     */
    Entry find(String name);

    /**
     * Downloads one provider's jar.
     *
     * @param entry the provider to download
     * @param dir where the jar is written
     * @return the path written
     * @throws IOException when the download or the write fails
     */
    Path download(Entry entry, Path dir) throws IOException;

    /** One installable provider jar: a human-readable {@code name}, the {@code assetName} it will
     *  be saved as, the {@code downloadUrl} to fetch it from, and the release's {@code version}
     *  (leading {@code v} already stripped; {@code null} when the release carried no tag). */
    final class Entry {
        /** The repo name, which is what a person sees and what {@code find} looks up. */
        public final String name;
        /** The file name the jar is saved as. */
        public final String assetName;
        /** Where the jar is fetched from. */
        public final String downloadUrl;
        /** The release version, leading {@code v} stripped, or null when the release had no tag. */
        public final String version;

        /**
         * @param name the repo name
         * @param assetName the file name the jar is saved as
         * @param downloadUrl where the jar is fetched from
         * @param version the release version, or null when the release had no tag
         */
        public Entry(String name, String assetName, String downloadUrl, String version) {
            this.name = name;
            this.assetName = assetName;
            this.downloadUrl = downloadUrl;
            this.version = version;
        }
    }
}
