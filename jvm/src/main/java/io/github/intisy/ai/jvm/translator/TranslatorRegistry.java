package io.github.intisy.ai.jvm.translator;

import io.github.intisy.ai.ir.spi.Translator;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
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
 * <p>{@link #load} hands back the discovered translators directly rather than a wrapping
 * registry object, so the backing {@link URLClassLoader} lives in static state until {@link
 * #close()} releases it -- {@code ProviderRegistry}'s equivalent discipline (loader stays open
 * for the whole usage window, closed once by the caller) with no instance to hang it off.
 */
public final class TranslatorRegistry {

    private static URLClassLoader classLoader;

    private TranslatorRegistry() {
    }

    /**
     * Scans {@code directory} for {@code *.jar} files and discovers every {@link Translator} they
     * register via {@code ServiceLoader}. A missing or empty directory yields an empty list (not
     * an error): zero translators installed is a valid, common state.
     */
    public static List<Translator> load(File directory) {
        File[] jarFiles = directory.isDirectory() ? directory.listFiles((dir, name) -> name.endsWith(".jar")) : null;
        if (jarFiles == null || jarFiles.length == 0) {
            classLoader = null;
            return Collections.emptyList();
        }

        URL[] urls = new URL[jarFiles.length];
        for (int i = 0; i < jarFiles.length; i++) {
            try {
                urls[i] = jarFiles[i].toURI().toURL();
            } catch (MalformedURLException e) {
                throw new IllegalStateException("unreadable translator jar path: " + jarFiles[i], e);
            }
        }

        // Deliberately NOT try-with-resources: the loader must stay open until close() is called
        // (see the class javadoc), so a translator referencing a class only from a not-yet-run
        // code path can still have it defined on demand.
        classLoader = new URLClassLoader(urls, TranslatorRegistry.class.getClassLoader());
        List<Translator> loaded = new ArrayList<>();
        for (Translator translator : ServiceLoader.load(Translator.class, classLoader)) {
            loaded.add(translator);
        }
        return loaded;
    }

    /** Releases the {@link URLClassLoader} backing the most recent {@link #load}, if any. */
    public static void close() throws IOException {
        if (classLoader != null) {
            classLoader.close();
            classLoader = null;
        }
    }
}
