package io.github.intisy.ai.exampleserver;

import io.github.intisy.ai.exampleserver.discovery.ProviderDiscovery;
import io.github.intisy.ai.jvm.provider.ProviderRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderDiscoveryTest {

    @Test
    void resolvesRegistryFromStagedDir(@TempDir Path dir) throws Exception {
        // Copy the staged example-provider jar (Gradle put it under exampleserver.providersDir)
        // into a fresh dir, then discovery must find it with org-fetch OFF.
        String staged = System.getProperty("exampleserver.providersDir");
        assertNotNull(staged, "exampleserver.providersDir must be set by Gradle test task");
        Path src = null;
        for (Path p : (Iterable<Path>) Files.list(Path.of(staged))::iterator) {
            if (p.getFileName().toString().endsWith(".jar")) { src = p; break; }
        }
        assertNotNull(src, "a provider jar must be staged");
        Files.copy(src, dir.resolve(src.getFileName()));

        try (ProviderRegistry reg = ProviderDiscovery.resolve(dir)) {
            assertTrue(reg.listProviderIds().contains("echo"),
                    "discovery must find the echo provider: " + reg.listProviderIds());
        }
    }

    @Test
    void missingDirYieldsEmptyRegistryNotError(@TempDir Path parent) throws Exception {
        Path missing = parent.resolve("does-not-exist");
        try (ProviderRegistry reg = ProviderDiscovery.resolve(missing)) {
            assertTrue(reg.listProviderIds().isEmpty());
        }
    }
}
