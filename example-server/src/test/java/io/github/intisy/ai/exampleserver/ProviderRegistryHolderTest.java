package io.github.intisy.ai.exampleserver;

import io.github.intisy.ai.exampleserver.discovery.ProviderDiscovery;
import io.github.intisy.ai.exampleserver.discovery.ProviderRegistryHolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderRegistryHolderTest {
    @Test
    void refreshPicksUpNewlyAddedJar(@TempDir Path dir) throws Exception {
        ProviderRegistryHolder holder = new ProviderRegistryHolder(ProviderDiscovery.resolve(dir));
        assertTrue(holder.listProviderIds().isEmpty());

        String staged = System.getProperty("exampleserver.providersDir");
        Path jar = null;
        for (Path p : (Iterable<Path>) Files.list(Path.of(staged))::iterator) {
            if (p.getFileName().toString().endsWith(".jar")) { jar = p; break; }
        }
        Files.copy(jar, dir.resolve(jar.getFileName()));

        holder.refresh(dir);
        assertFalse(holder.listProviderIds().isEmpty());
        assertTrue(holder.listProviderIds().contains("echo"));

        // refresh() deliberately never closes the registry it swaps out (see the holder's
        // javadoc), so the current one -- whose URLClassLoader now holds the jar in @TempDir
        // open -- must be closed here, or @TempDir's own cleanup fails on Windows (file still
        // in use), exactly like ProviderRegistryTest's fromDirectory tests already document.
        holder.get().close();
    }
}
