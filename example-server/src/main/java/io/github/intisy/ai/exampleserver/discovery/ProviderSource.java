package io.github.intisy.ai.exampleserver.discovery;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * A source of installable provider jars, decoupled from any particular hosting scheme (GitHub
 * releases today; could be a private registry or a local index tomorrow). {@link #list()} is a
 * pure lookup — it never writes to disk — so callers (e.g. an install API added in a later task)
 * can show what's available before committing to a download.
 */
public interface ProviderSource {

    /** Available providers, discovered fresh on every call. Never {@code null}; empty when none
     *  are available or the source is unreachable. */
    List<Entry> list();

    /** Targeted lookup of a single installable provider by its {@code name} (repo name), scanning
     *  only that one repo's latest release -- so an install works even when the full org scan is
     *  rate-limited or cached-empty. Null when not found. */
    Entry find(String name);

    /** Downloads {@code entry}'s jar into {@code dir}, returning the path written. */
    Path download(Entry entry, Path dir) throws IOException;

    /** One installable provider jar: a human-readable {@code name}, the {@code assetName} it will
     *  be saved as, the {@code downloadUrl} to fetch it from, and the release's {@code version}
     *  (leading {@code v} already stripped; {@code null} when the release carried no tag). */
    final class Entry {
        public final String name;
        public final String assetName;
        public final String downloadUrl;
        public final String version;

        public Entry(String name, String assetName, String downloadUrl, String version) {
            this.name = name;
            this.assetName = assetName;
            this.downloadUrl = downloadUrl;
            this.version = version;
        }
    }
}
