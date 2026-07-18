package io.github.intisy.ai.exampleserver.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the {@code <jar>.version} sidecar helper in isolation -- no HTTP, no registry. */
class InstalledVersionsTest {

    @Test
    void writeThenReadRoundTrips(@TempDir Path dir) throws IOException {
        Path jar = dir.resolve("some-provider.jar");
        Files.write(jar, new byte[] {1, 2, 3});

        InstalledVersions.write(jar, "1.5.1");

        assertEquals("1.5.1", InstalledVersions.read(jar));
        assertTrue(Files.exists(InstalledVersions.sidecarFor(jar)));
        assertEquals("some-provider.jar.version", InstalledVersions.sidecarFor(jar).getFileName().toString());
    }

    @Test
    void readWithNoSidecarIsNull(@TempDir Path dir) {
        Path jar = dir.resolve("no-sidecar-provider.jar");
        assertNull(InstalledVersions.read(jar), "a legacy install with no sidecar reads back null, not a crash");
    }

    @Test
    void writeWithNullVersionIsANoOp(@TempDir Path dir) throws IOException {
        Path jar = dir.resolve("no-tag-provider.jar");
        Files.write(jar, new byte[] {1});

        InstalledVersions.write(jar, null);

        assertFalse(Files.exists(InstalledVersions.sidecarFor(jar)),
                "no tag_name on the release means nothing meaningful to record");
        assertNull(InstalledVersions.read(jar));
    }

    @Test
    void normalizeStripsOnlyALeadingV() {
        assertEquals("1.5.1", InstalledVersions.normalize("v1.5.1"));
        assertEquals("1.5.1", InstalledVersions.normalize("V1.5.1"));
        assertEquals("1.5.1", InstalledVersions.normalize("1.5.1"));
        assertNull(InstalledVersions.normalize(null));
    }

    @Test
    void updateAvailableIsTrueOnlyWhenInstalledAndBothVersionsKnownAndDiffer() {
        assertTrue(InstalledVersions.updateAvailable(true, "1.5.0", "1.5.1"));
        assertFalse(InstalledVersions.updateAvailable(true, "1.5.1", "1.5.1"), "equal versions -> no update");
        assertFalse(InstalledVersions.updateAvailable(true, "v1.5.1", "1.5.1"), "same version, just v-prefixed");
        assertFalse(InstalledVersions.updateAvailable(true, null, "1.5.1"), "legacy install, sidecar absent");
        assertFalse(InstalledVersions.updateAvailable(true, "1.5.0", null), "latest version unknown");
        assertFalse(InstalledVersions.updateAvailable(false, "1.5.0", "1.5.1"), "not installed at all");
    }
}
