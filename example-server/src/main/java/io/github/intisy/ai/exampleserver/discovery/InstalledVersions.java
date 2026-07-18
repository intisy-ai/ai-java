package io.github.intisy.ai.exampleserver.discovery;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Reads/writes the small {@code <jar>.version} sidecar file written next to an installed
 * provider jar, recording which version is actually on disk so the dashboard can compare it
 * against the latest version an org scan reports and offer an update. A sidecar-less jar (an
 * install from before this feature existed) reads back {@code null} -- callers treat that as
 * "version unknown", never as a crash or a false "update available". Public: read/normalize/
 * updateAvailable are also called from {@code api.ManagementApi} (a different package).
 */
public final class InstalledVersions {

    private InstalledVersions() {
    }

    public static Path sidecarFor(Path jarPath) {
        return jarPath.resolveSibling(jarPath.getFileName().toString() + ".version");
    }

    /** Writes {@code version} as the sidecar for {@code jarPath}. No-op when {@code version} is
     *  {@code null} (the release carried no {@code tag_name}) -- there is nothing meaningful to
     *  record, and an absent sidecar already reads back as "unknown" via {@link #read}. */
    public static void write(Path jarPath, String version) throws IOException {
        if (version == null) return;
        Files.write(sidecarFor(jarPath), (version + "\n").getBytes(StandardCharsets.UTF_8));
    }

    /** The recorded installed version for {@code jarPath}, or {@code null} if no sidecar exists
     *  (legacy install predating this feature) or it can't be read. */
    public static String read(Path jarPath) {
        Path sidecar = sidecarFor(jarPath);
        if (!Files.exists(sidecar)) return null;
        try {
            List<String> lines = Files.readAllLines(sidecar, StandardCharsets.UTF_8);
            if (lines.isEmpty()) return null;
            String first = lines.get(0).trim();
            return first.isEmpty() ? null : first;
        } catch (IOException e) {
            return null;
        }
    }

    /** Plain string compare only, per the "no semver library" rule: strips a single leading
     *  {@code v}/{@code V} before comparing so a stray-formatted version still matches. */
    public static String normalize(String version) {
        if (version == null || version.isEmpty()) return version;
        char first = version.charAt(0);
        return (first == 'v' || first == 'V') ? version.substring(1) : version;
    }

    /** {@code true} only when the provider is installed, both versions are known, and they
     *  differ after normalization -- a legacy install with no sidecar (installedVersion null)
     *  never reports an update, since there is nothing to compare against. */
    public static boolean updateAvailable(boolean installed, String installedVersion, String latestVersion) {
        if (!installed || installedVersion == null || latestVersion == null) return false;
        return !normalize(installedVersion).equals(normalize(latestVersion));
    }
}
