package io.github.intisy.ai.jvm.provider;

import io.github.intisy.ai.jvm.plugin.JarServices;
import io.github.intisy.ai.shared.logic.HandlerResolvers;
import io.github.intisy.ai.shared.routing.HandlerResolver;
import io.github.intisy.ai.auth.contracts.Provider;
import io.github.intisy.ai.ir.spi.IrHandler;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Runtime {@link Provider} discovery: scans a directory for {@code *.jar} files, loads them on
 * a dedicated class loader parented to the host (so a jar's {@code Provider} implementation sees
 * the exact same {@code Provider}/{@code ProxyHandler}/etc. classes as the host, avoiding a
 * classloader-identity mismatch), and discovers implementations
 * via {@code ServiceLoader.load(Provider.class, classLoader)}. A provider jar registers itself
 * the usual JVM way: {@code META-INF/services/io.github.intisy.ai.auth.contracts.Provider}
 * listing its implementation class(es).
 *
 * <p>Dropping a new provider jar into the directory (and rebuilding the registry, see
 * {@link #fromDirectory}) requires zero ai-java code changes: this is the seam real provider
 * modules (e.g. {@code stub-auth}) load through. This class only proves the discovery + wiring.
 *
 * <p>The scan, the class loading and the discovery itself live in {@link JarServices}, which also
 * documents when a registry may be closed and what closing one costs.
 */
public final class ProviderRegistry implements Closeable {

    private final JarServices<Provider> discovered;

    private ProviderRegistry(JarServices<Provider> discovered) {
        this.discovered = discovered;
    }

    /**
     * Scans {@code providersDir} for {@code *.jar} files and discovers every {@link Provider}
     * they register via {@code ServiceLoader}. A missing or empty directory yields an empty
     * registry (not an error): zero providers installed is a valid, common state (e.g. a
     * fresh install before any provider jar has been dropped in).
     *
     * @param providersDir the directory to scan
     * @return the registry, empty when the directory holds no jars
     */
    public static ProviderRegistry fromDirectory(Path providersDir) {
        return new ProviderRegistry(JarServices.fromDirectory(providersDir, Provider.class, Provider::id));
    }

    /** {@return a registry with no providers, the valid state before any jar is installed} */
    public static ProviderRegistry empty() {
        return new ProviderRegistry(JarServices.<Provider>empty());
    }

    /** {@return the discovered providers, in discovery order} */
    public List<Provider> providers() {
        return discovered.all();
    }

    /** {@return the discovered providers, adapted into a {@link HandlerResolver}} */
    public HandlerResolver asHandlerResolver() {
        return HandlerResolvers.fromHandlers(new ArrayList<IrHandler>(discovered.all()));
    }

    /** {@return the id of every discovered provider, in discovery order} */
    public List<String> listProviderIds() {
        return discovered.all().stream().map(Provider::id).collect(Collectors.toList());
    }

    /**
     * {@return the discovered provider with this id, or {@code null} when none has it}
     *
     * @param id the provider id to look for
     */
    public Provider get(String id) {
        for (Provider provider : discovered.all()) {
            if (provider.id().equals(id)) return provider;
        }
        return null;
    }

    /**
     * {@return the jar registering this provider, or {@code null} when it is not loaded}
     *
     * @param providerId the provider whose jar is wanted
     */
    public Path jarFor(String providerId) {
        return discovered.jarFor(providerId);
    }

    /**
     * Releases the class loader backing this registry's jar-discovered providers, if any. See
     * {@link JarServices#close} for when that is safe.
     *
     * @throws IOException when the loader cannot be released
     */
    @Override
    public void close() throws IOException {
        discovered.close();
    }
}
