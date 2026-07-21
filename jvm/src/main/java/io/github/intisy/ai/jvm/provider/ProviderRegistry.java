package io.github.intisy.ai.jvm.provider;

import io.github.intisy.ai.shared.logic.HandlerResolvers;
import io.github.intisy.ai.shared.routing.HandlerResolver;
import io.github.intisy.ai.shared.routing.Provider;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

/**
 * Runtime {@link Provider} discovery: scans a directory for {@code *.jar} files, loads them on
 * a dedicated {@link URLClassLoader} (parented to this class's own loader, so a jar's
 * {@code Provider} implementation sees the exact same {@code Provider}/{@code ProxyHandler}/etc.
 * classes as the host, avoiding a classloader-identity mismatch), and discovers implementations
 * via {@code ServiceLoader.load(Provider.class, classLoader)}. A provider jar registers itself
 * the usual JVM way: {@code META-INF/services/io.github.intisy.ai.shared.routing.Provider}
 * listing its implementation class(es).
 *
 * <p>Dropping a new provider jar into the directory (and rebuilding the registry, see
 * {@link #fromDirectory}) requires zero ai-java code changes: this is the seam real provider
 * modules (e.g. {@code stub-auth}) load through. This class only proves the discovery + wiring.
 *
 * <p>The {@link URLClassLoader} backing a jar-discovered provider is kept OPEN for the lifetime
 * of the registry (see {@link #close()}). Closing it as soon as {@code ServiceLoader} finishes
 * constructing the providers only releases the loader's own bookkeeping; it does NOT unload the
 * classes it already defined, but it DOES let a subsequent {@code defineClass} for a class the
 * provider jar references only from inside a method body that hasn't run yet (a helper type, a
 * custom exception on an error branch, a lazily-initialized enum, ...) fail with
 * {@link NoClassDefFoundError} once that code path finally executes, because the loader can no
 * longer read the jar to define it. Callers that
 * discard a registry (e.g. rebuilding it after a provider jar is swapped) must call
 * {@link #close()} once they're certain no in-flight request still holds a reference to one of
 * its providers.
 */
public final class ProviderRegistry implements Closeable {

    private final List<Provider> providers;
    private final URLClassLoader classLoader; // null when no provider jars were found
    private final Map<String, Path> jars; // provider id -> the jar file that registers it

    private ProviderRegistry(List<Provider> providers, URLClassLoader classLoader, Map<String, Path> jars) {
        this.providers = providers;
        this.classLoader = classLoader;
        this.jars = jars;
    }

    /**
     * Scans {@code providersDir} for {@code *.jar} files and discovers every {@link Provider}
     * they register via {@code ServiceLoader}. A missing or empty directory yields an empty
     * registry (not an error): zero providers installed is a valid, common state (e.g. a
     * fresh install before any provider jar has been dropped in).
     */
    public static ProviderRegistry fromDirectory(Path providersDir) {
        File dir = providersDir.toFile();
        File[] jarFiles = dir.isDirectory() ? dir.listFiles((d, name) -> name.endsWith(".jar")) : null;
        if (jarFiles == null || jarFiles.length == 0) {
            return new ProviderRegistry(Collections.emptyList(), null, Collections.emptyMap());
        }

        URL[] urls = new URL[jarFiles.length];
        for (int i = 0; i < jarFiles.length; i++) {
            try {
                urls[i] = jarFiles[i].toURI().toURL();
            } catch (MalformedURLException e) {
                throw new IllegalStateException("unreadable provider jar path: " + jarFiles[i], e);
            }
        }

        Map<String, Path> jarById = probeJarsForProviderIds(jarFiles, urls);

        // Parent = this class's own loader (not the system/bootstrap loader), so a jar's
        // Provider implementation resolves the shared Provider/ProxyHandler/HandlerCtx classes
        // to the SAME classes this host already has loaded, rather than a second, incompatible
        // copy that ServiceLoader would silently fail to cast.
        //
        // Deliberately NOT try-with-resources: the loader must stay open for the registry's
        // whole lifetime (see the class javadoc) so any class a provider references only from a
        // not-yet-executed method body can still be defined on demand. The caller closes it via
        // ProviderRegistry.close() once the registry itself is discarded.
        URLClassLoader classLoader = new URLClassLoader(urls, ProviderRegistry.class.getClassLoader());
        List<Provider> loaded = new ArrayList<>();
        for (Provider provider : ServiceLoader.load(Provider.class, classLoader)) {
            loaded.add(provider);
        }
        return new ProviderRegistry(loaded, classLoader, jarById);
    }

    /**
     * Attributes each discovered provider id to the single jar file that registers it. A combined
     * {@code ServiceLoader} over all jars at once (as {@link #fromDirectory} builds for actual
     * serving, right below) can't tell WHICH jar produced a given id. So, per jar, a short-lived
     * child {@link URLClassLoader} (parented to the host, same as the real combined loader) is
     * opened over just that ONE jar's URL, {@code ServiceLoader.load(Provider.class, probe)} is
     * run, and every id it yields is recorded against that jar before the probe loader is closed.
     * This never touches the real, long-lived combined registry built afterward; the probe
     * providers are throwaway, used only for their {@code id()}.
     */
    private static Map<String, Path> probeJarsForProviderIds(File[] jarFiles, URL[] urls) {
        Map<String, Path> jarById = new HashMap<>();
        for (int i = 0; i < jarFiles.length; i++) {
            try (URLClassLoader probe = new URLClassLoader(new URL[] {urls[i]}, ProviderRegistry.class.getClassLoader())) {
                for (Provider provider : ServiceLoader.load(Provider.class, probe)) {
                    jarById.put(provider.id(), jarFiles[i].toPath());
                }
            } catch (IOException e) {
                throw new IllegalStateException("failed to probe provider jar: " + jarFiles[i], e);
            }
        }
        return jarById;
    }

    /** No providers directory configured (or none found yet): a valid, zero-provider state. */
    public static ProviderRegistry empty() {
        return new ProviderRegistry(Collections.emptyList(), null, Collections.emptyMap());
    }

    /** Adapts the discovered providers into a {@link HandlerResolver} via {@code fromProviders}. */
    public HandlerResolver asHandlerResolver() {
        return HandlerResolvers.fromProviders(providers);
    }

    /** The ids of every discovered provider, in discovery order. */
    public List<String> listProviderIds() {
        return providers.stream().map(Provider::id).collect(Collectors.toList());
    }

    /** The discovered {@link Provider} whose {@link Provider#id()} equals {@code id}, or {@code null}. */
    public Provider get(String id) {
        for (Provider provider : providers) {
            if (provider.id().equals(id)) return provider;
        }
        return null;
    }

    /** The jar file that registers {@code providerId}, or {@code null} if no such provider is loaded. */
    public Path jarFor(String providerId) {
        return jars.get(providerId);
    }

    /**
     * Releases the {@link URLClassLoader} backing this registry's jar-discovered providers, if
     * any. Safe to call on a registry with no provider jars (e.g. {@link #empty()}): a no-op in
     * that case. {@code URLClassLoader.close()} is itself safe to call more than once, but
     * callers should only close a registry once nothing still routes through its providers:
     * closing releases the loader's open jar handles (letting the jar be deleted/replaced on
     * Windows) without unloading the classes it already defined, so already-running requests
     * are unaffected, but any NOT-yet-executed code path that still needs to define a new class
     * from the jar (see the class javadoc) will fail once the loader is closed.
     */
    @Override
    public void close() throws IOException {
        if (classLoader != null) {
            classLoader.close();
        }
    }
}
