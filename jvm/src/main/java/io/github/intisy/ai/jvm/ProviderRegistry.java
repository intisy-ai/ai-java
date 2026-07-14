package io.github.intisy.ai.jvm;

import io.github.intisy.ai.shared.logic.HandlerResolvers;
import io.github.intisy.ai.shared.routing.HandlerResolver;
import io.github.intisy.ai.shared.routing.Provider;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

/**
 * Runtime {@link Provider} discovery: scans a directory for {@code *.jar} files, loads them on
 * a dedicated {@link URLClassLoader} (parented to this class's own loader, so a jar's
 * {@code Provider} implementation sees the exact same {@code Provider}/{@code ProxyHandler}/etc.
 * classes as the host — no classloader-identity mismatch), and discovers implementations via
 * {@code ServiceLoader.load(Provider.class, classLoader)}. A provider jar registers itself the
 * usual JVM way: {@code META-INF/services/io.github.intisy.ai.shared.routing.Provider} listing
 * its implementation class(es).
 *
 * <p>Dropping a new provider jar into the directory (and rebuilding the registry — see
 * {@link #fromDirectory}) requires zero ai-java code changes: this is the seam Task 4+ (the
 * actual provider modules, e.g. {@code stub-auth}) load through. This class only proves the
 * discovery + wiring; the full drop-a-jar-while-running proof lives in Task 4.
 */
public final class ProviderRegistry {

    private final List<Provider> providers;

    private ProviderRegistry(List<Provider> providers) {
        this.providers = providers;
    }

    /**
     * Scans {@code providersDir} for {@code *.jar} files and discovers every {@link Provider}
     * they register via {@code ServiceLoader}. A missing or empty directory yields an empty
     * registry (not an error) — zero providers installed is a valid, common state (e.g. a
     * fresh install before any provider jar has been dropped in).
     */
    public static ProviderRegistry fromDirectory(Path providersDir) {
        File dir = providersDir.toFile();
        File[] jars = dir.isDirectory() ? dir.listFiles((d, name) -> name.endsWith(".jar")) : null;
        if (jars == null || jars.length == 0) {
            return new ProviderRegistry(Collections.emptyList());
        }

        URL[] urls = new URL[jars.length];
        for (int i = 0; i < jars.length; i++) {
            try {
                urls[i] = jars[i].toURI().toURL();
            } catch (MalformedURLException e) {
                throw new IllegalStateException("unreadable provider jar path: " + jars[i], e);
            }
        }
        // Parent = this class's own loader (not the system/bootstrap loader), so a jar's
        // Provider implementation resolves the shared Provider/ProxyHandler/HandlerCtx classes
        // to the SAME classes this host already has loaded, rather than a second, incompatible
        // copy that ServiceLoader would silently fail to cast.
        List<Provider> loaded = new ArrayList<>();
        // Closed once every provider is instantiated (not left open): an open URLClassLoader
        // keeps its jars' file handles open, which on Windows blocks the jar file from being
        // deleted/replaced later (e.g. a temp-dir cleanup, or a provider jar being swapped out)
        // even though the already-defined Provider classes/instances stay perfectly usable —
        // closing a URLClassLoader releases its resources, it doesn't unload loaded classes.
        try (URLClassLoader classLoader = new URLClassLoader(urls, ProviderRegistry.class.getClassLoader())) {
            for (Provider provider : ServiceLoader.load(Provider.class, classLoader)) {
                loaded.add(provider);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to close provider classloader for " + providersDir, e);
        }
        return new ProviderRegistry(loaded);
    }

    /** No providers directory configured (or none found yet) — a valid, zero-provider state. */
    public static ProviderRegistry empty() {
        return new ProviderRegistry(Collections.emptyList());
    }

    /** Adapts the discovered providers into a {@link HandlerResolver} via {@code fromProviders}. */
    public HandlerResolver asHandlerResolver() {
        return HandlerResolvers.fromProviders(providers);
    }

    /** The ids of every discovered provider, in discovery order. */
    public List<String> listProviderIds() {
        return providers.stream().map(Provider::id).collect(Collectors.toList());
    }
}
