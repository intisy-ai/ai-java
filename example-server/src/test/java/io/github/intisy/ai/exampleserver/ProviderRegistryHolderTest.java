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

        // refresh() now closes the registry it swaps OUT (see the holder's javadoc), but the
        // NEW current registry's URLClassLoader still holds the jar in @TempDir open -- it must
        // be closed here too, or @TempDir's own cleanup fails on Windows (file still in use),
        // exactly like ProviderRegistryTest's fromDirectory tests already document.
        holder.get().close();
    }

    @Test
    void refreshClosesThePreviousRegistrySoItsJarCanBeDeletedAfterward(@TempDir Path dir, @TempDir Path otherDir)
            throws Exception {
        ProviderRegistryHolder holder = new ProviderRegistryHolder(ProviderDiscovery.resolve(dir));

        String staged = System.getProperty("exampleserver.providersDir");
        Path srcJar = null;
        for (Path p : (Iterable<Path>) Files.list(Path.of(staged))::iterator) {
            if (p.getFileName().toString().endsWith(".jar")) { srcJar = p; break; }
        }
        Path jar = dir.resolve(srcJar.getFileName());
        Files.copy(srcJar, jar);

        holder.refresh(dir); // builds a registry (call it R1) whose URLClassLoader opens `jar`
        assertTrue(holder.listProviderIds().contains("echo"));

        // Refresh again from a DIFFERENT, jar-free directory: the new current registry (R2) opens
        // nothing, so it cannot itself be blocking a delete of `jar`. If refresh() closes R1 (the
        // one it swaps out) as it now must, R1's hold on `jar` is released here.
        holder.refresh(otherDir);
        assertTrue(holder.listProviderIds().isEmpty());

        // If R1 were still open (the old, leak-forever behavior), this delete would fail on
        // Windows with a sharing violation -- proving refresh() actually closes the registry it
        // replaces, not just the registry uninstall()/update() close explicitly themselves.
        assertTrue(Files.deleteIfExists(jar), "jar should be deletable once the prior registry closed");

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
