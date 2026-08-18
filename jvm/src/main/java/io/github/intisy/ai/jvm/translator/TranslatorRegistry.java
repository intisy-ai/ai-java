package io.github.intisy.ai.jvm.translator;

import io.github.intisy.ai.ir.spi.Translator;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Runtime {@link Translator} discovery: scans a directory for {@code *.jar} files, loads them on
 * a dedicated {@link URLClassLoader} (parented to this class's own loader, so a jar's {@code
 * Translator} implementation sees the exact same IR types the host already has loaded), and
 * discovers implementations via {@code ServiceLoader.load(Translator.class, classLoader)}. A
 * translator jar registers itself the usual JVM way: {@code
 * META-INF/services/io.github.intisy.ai.ir.spi.Translator} listing its implementation class.
 *
 * <p>Mirrors {@code ProviderRegistry} one level up: each {@link #fromDirectory} call returns its
 * OWN instance holding its OWN {@link URLClassLoader}, so one caller's {@link #close()} can never
 * strand a translator a different caller already obtained from a different instance -- unlike a
 * single shared static loader, where closing on behalf of one caller silently breaks another's
 * already-handed-out translator the moment it needs to resolve a class it hasn't touched yet.
 */
public final class TranslatorRegistry implements Closeable {

    private final List<Translator> translators;
    private final URLClassLoader classLoader; // null when no translator jars were found

    private TranslatorRegistry(List<Translator> translators, URLClassLoader classLoader) {
        this.translators = translators;
        this.classLoader = classLoader;
    }

    /**
     * Scans {@code directory} for {@code *.jar} files and discovers every {@link Translator} they
     * register via {@code ServiceLoader}. A missing or empty directory yields an empty registry
     * (not an error): zero translators installed is a valid, common state.
     */
    public static TranslatorRegistry fromDirectory(Path directory) {
        File dir = directory.toFile();
        File[] jarFiles = dir.isDirectory() ? dir.listFiles((d, name) -> name.endsWith(".jar")) : null;
        if (jarFiles == null || jarFiles.length == 0) {
            return new TranslatorRegistry(Collections.emptyList(), null);
        }

        URL[] urls = new URL[jarFiles.length];
        for (int i = 0; i < jarFiles.length; i++) {
            try {
                urls[i] = jarFiles[i].toURI().toURL();
            } catch (MalformedURLException e) {
                throw new IllegalStateException("unreadable translator jar path: " + jarFiles[i], e);
            }
        }

        // Deliberately NOT try-with-resources: the loader must stay open for this registry's
        // whole lifetime so a translator referencing a class only from a not-yet-run code path
        // can still have it defined on demand. The caller closes it via close() once THIS
        // registry is discarded; that can never affect any other registry's own loader.
        URLClassLoader classLoader = new URLClassLoader(urls, TranslatorRegistry.class.getClassLoader());
        List<Translator> loaded = new ArrayList<>();
        for (Translator translator : ServiceLoader.load(Translator.class, classLoader)) {
            loaded.add(translator);
        }
        return new TranslatorRegistry(loaded, classLoader);
    }

    /** No translators directory configured (or none found yet): a valid, zero-translator state. */
    public static TranslatorRegistry empty() {
        return new TranslatorRegistry(Collections.emptyList(), null);
    }

    /** Every {@link Translator} this registry discovered, in discovery order. */
    public List<Translator> translators() {
        return translators;
    }

    /**
     * Releases the {@link URLClassLoader} backing this registry's jar-discovered translators, if
     * any. Safe to call on a registry with no translator jars (e.g. {@link #empty()}): a no-op in
     * that case. Callers should only close a registry once nothing still needs its translators:
     * closing releases the loader's open jar handles (letting the jar be deleted/replaced on
     * Windows) without unloading classes it already defined, so already-running requests are
     * unaffected, but any NOT-yet-executed code path that still needs to define a new class from
     * the jar (e.g. a translator's lazily-referenced anonymous {@code StreamDecoder}) will fail
     * once the loader is closed.
     */
    @Override
    public void close() throws IOException {
        if (classLoader != null) {
            classLoader.close();
        }
    }
}
