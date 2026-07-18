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
 * install from before this feature existed) reads back {@code null} ("version unknown", a
 * crash-free read); an unknown installed version IS offered the update so legacy installs can be
 * brought current (see {@link #updateAvailable}). Public: read/normalize/updateAvailable are also
 * called from {@code api.ManagementApi} (a different package).
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

    /** {@code true} when the provider is installed, the latest version is known, and either the
     *  recorded installed version differs from it OR no version was recorded at all. A legacy
     *  install with no sidecar (installedVersion null) IS offered the update: its on-disk version
     *  can't be confirmed current, and performing the update writes the sidecar so every later
     *  check compares precisely (self-healing). Without this, providers installed before the
     *  sidecar existed could never be updated from the console. */
    public static boolean updateAvailable(boolean installed, String installedVersion, String latestVersion) {
        if (!installed || latestVersion == null) return false;
        if (installedVersion == null) return true;
        return !normalize(installedVersion).equals(normalize(latestVersion));
    }
}
