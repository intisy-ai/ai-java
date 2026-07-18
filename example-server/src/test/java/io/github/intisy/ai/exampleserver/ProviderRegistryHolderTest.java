package io.github.intisy.ai.exampleserver;

import io.github.intisy.ai.exampleserver.discovery.ProviderDiscovery;
import io.github.intisy.ai.exampleserver.discovery.ProviderRegistryHolder;
import io.github.intisy.ai.shared.routing.Provider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderRegistryHolderTest {
    @Test
    void getByIdPassesThroughToTheCurrentRegistry(@TempDir Path dir) throws Exception {
        ProviderRegistryHolder holder = new ProviderRegistryHolder(ProviderDiscovery.resolve(dir));
        assertNull(holder.get("echo"), "no jar staged yet -- nothing discovered");

        String staged = System.getProperty("exampleserver.providersDir");
        Path jar = null;
        for (Path p : (Iterable<Path>) Files.list(Path.of(staged))::iterator) {
            if (p.getFileName().toString().endsWith(".jar")) { jar = p; break; }
        }
        Files.copy(jar, dir.resolve(jar.getFileName()));
        holder.refresh(dir);

        Provider found = holder.get("echo");
        assertNotNull(found);
        assertEquals("echo", found.id());
        assertNull(holder.get("nope"));

        holder.get().close();
    }

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

    @Test
    void uninstallClosesRegistryBeforeDeletingJar_thenRebuildsWithoutIt(@TempDir Path dir) throws Exception {
        ProviderRegistryHolder holder = new ProviderRegistryHolder(ProviderDiscovery.resolve(dir));

        String staged = System.getProperty("exampleserver.providersDir");
        Path srcJar = null;
        for (Path p : (Iterable<Path>) Files.list(Path.of(staged))::iterator) {
            if (p.getFileName().toString().endsWith(".jar")) { srcJar = p; break; }
        }
        Path jar = dir.resolve(srcJar.getFileName());
        Files.copy(srcJar, jar);

        holder.refresh(dir);
        assertTrue(holder.listProviderIds().contains("echo"));

        boolean ok = holder.uninstall("echo", dir);

        assertTrue(ok);
        // On Windows, this would fail if the URLClassLoader hadn't been close()'d before the
        // delete -- a still-open jar handle blocks Files.deleteIfExists with a sharing violation.
        assertFalse(Files.exists(jar), "jar should be deleted from disk");
        assertFalse(holder.listProviderIds().contains("echo"));

        holder.get().close();
    }

    @Test
    void uninstallUnknownProviderIdIsANoOp(@TempDir Path dir) {
        ProviderRegistryHolder holder = new ProviderRegistryHolder(ProviderDiscovery.resolve(dir));
        assertFalse(holder.uninstall("does-not-exist", dir));
    }
}
