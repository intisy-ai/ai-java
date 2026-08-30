package io.github.intisy.ai.jvm.translator;

import io.github.intisy.ai.ir.spi.Translator;
import io.github.intisy.ai.jvm.plugin.JarServices;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Runtime {@link Translator} discovery: scans a directory for {@code *.jar} files, loads them on
 * a dedicated class loader parented to the host (so a jar's {@code Translator} implementation sees
 * the exact same IR types the host already has loaded), and
 * discovers implementations via {@code ServiceLoader.load(Translator.class, classLoader)}. A
 * translator jar registers itself the usual JVM way: {@code
 * META-INF/services/io.github.intisy.ai.ir.spi.Translator} listing its implementation class.
 *
 * <p>The scan, the class loading and the discovery itself live in {@link JarServices}. Each
 * {@link #fromDirectory} call gets its OWN loader, so one caller's {@link #close()} can never
 * strand a translator another caller already holds.
 */
public final class TranslatorRegistry implements Closeable {

    private final JarServices<Translator> discovered;

    private TranslatorRegistry(JarServices<Translator> discovered) {
        this.discovered = discovered;
    }

    /**
     * Scans {@code directory} for {@code *.jar} files and discovers every {@link Translator} they
     * register via {@code ServiceLoader}. A missing or empty directory yields an empty registry
     * (not an error): zero translators installed is a valid, common state.
     *
     * @param directory the directory to scan
     * @return the registry, empty when the directory holds no jars
     */
    public static TranslatorRegistry fromDirectory(Path directory) {
        // No id function: the Translator SPI has no notion of one, so nothing attributes a
        // translator to the jar it came from and nothing needs to.
        return new TranslatorRegistry(JarServices.fromDirectory(directory, Translator.class, null));
    }

    /** {@return a registry with no translators, the valid state before any jar is installed} */
    public static TranslatorRegistry empty() {
        return new TranslatorRegistry(JarServices.<Translator>empty());
    }

    /** {@return every translator this registry discovered, in discovery order} */
    public List<Translator> translators() {
        return discovered.all();
    }

    /**
     * Releases the class loader backing this registry's jar-discovered translators, if any. See
     * {@link JarServices#close} for when that is safe.
     *
     * @throws IOException when the loader cannot be released
     */
    @Override
    public void close() throws IOException {
        discovered.close();
    }
}
