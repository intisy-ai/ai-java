package io.github.intisy.ai.jvm.proxy;

import io.github.intisy.ai.shared.routing.ProxyPlugin;
import io.github.intisy.ai.shared.routing.RoutingProfile;

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
 * Runtime {@link ProxyPlugin} discovery: scans a directory for {@code *.jar} files, loads them on
 * a dedicated {@link URLClassLoader} (parented to this class's own loader, so a jar's
 * {@code ProxyPlugin} implementation sees the exact same {@code ProxyPlugin}/{@code RoutingProfile}
 * classes as the host, avoiding a classloader-identity mismatch), and discovers implementations via
 * {@code ServiceLoader.load(ProxyPlugin.class, classLoader)}. A proxy jar registers itself the
 * usual JVM way: {@code META-INF/services/io.github.intisy.ai.shared.routing.ProxyPlugin} listing
 * its implementation class(es).
 *
 * <p>This is the proxy-side mirror of {@code ProviderRegistry}: same classloader discipline, same
 * rationale, keyed by {@link ProxyPlugin#id()} instead of a provider id.
 *
 * <p>The {@link URLClassLoader} backing a jar-discovered proxy is kept OPEN for the lifetime
 * of the registry (see {@link #close()}). Closing it as soon as {@code ServiceLoader} finishes
 * constructing the plugins only releases the loader's own bookkeeping; it does NOT unload the
 * classes it already defined, but it DOES let a subsequent {@code defineClass} for a class the
 * proxy jar references only from inside a method body that hasn't run yet (a helper type, a
 * custom exception on an error branch, a lazily-initialized enum, ...) fail with
 * {@link NoClassDefFoundError} once that code path finally executes, because the loader can no
 * longer read the jar to define it. Callers that
 * discard a registry (e.g. rebuilding it after a proxy jar is swapped) must call
 * {@link #close()} once they're certain no in-flight request still holds a reference to one of
 * its proxies.
 */
public final class ProxyRegistry implements Closeable {

    private final List<ProxyPlugin> plugins;
    private final URLClassLoader classLoader; // null when no proxy jars were found
    private final Map<String, Path> jars; // proxy id -> the jar file that registers it

    private ProxyRegistry(List<ProxyPlugin> plugins, URLClassLoader classLoader, Map<String, Path> jars) {
        this.plugins = plugins;
        this.classLoader = classLoader;
        this.jars = jars;
    }

    /**
     * Scans {@code proxiesDir} for {@code *.jar} files and discovers every {@link ProxyPlugin}
     * they register via {@code ServiceLoader}. A missing or empty directory yields an empty
     * registry (not an error): zero proxies installed is a valid, common state (e.g. a
     * fresh install before any proxy jar has been dropped in).
     */
    public static ProxyRegistry fromDirectory(Path proxiesDir) {
        File dir = proxiesDir.toFile();
        File[] jarFiles = dir.isDirectory() ? dir.listFiles((d, name) -> name.endsWith(".jar")) : null;
        if (jarFiles == null || jarFiles.length == 0) {
            return new ProxyRegistry(Collections.emptyList(), null, Collections.emptyMap());
        }

        URL[] urls = new URL[jarFiles.length];
        for (int i = 0; i < jarFiles.length; i++) {
            try {
                urls[i] = jarFiles[i].toURI().toURL();
            } catch (MalformedURLException e) {
                throw new IllegalStateException("unreadable proxy jar path: " + jarFiles[i], e);
            }
        }

        Map<String, Path> jarById = probeJarsForProxyIds(jarFiles, urls);

        // Parent = this class's own loader (not the system/bootstrap loader), so a jar's
        // ProxyPlugin implementation resolves the shared ProxyPlugin/RoutingProfile classes
        // to the SAME classes this host already has loaded, rather than a second, incompatible
        // copy that ServiceLoader would silently fail to cast.
        //
        // Deliberately NOT try-with-resources: the loader must stay open for the registry's
        // whole lifetime (see the class javadoc) so any class a proxy references only from a
        // not-yet-executed method body can still be defined on demand. The caller closes it via
        // ProxyRegistry.close() once the registry itself is discarded.
        URLClassLoader classLoader = new URLClassLoader(urls, ProxyRegistry.class.getClassLoader());
        List<ProxyPlugin> loaded = new ArrayList<>();
        for (ProxyPlugin plugin : ServiceLoader.load(ProxyPlugin.class, classLoader)) {
            loaded.add(plugin);
        }
        return new ProxyRegistry(loaded, classLoader, jarById);
    }

    /**
     * Attributes each discovered proxy id to the single jar file that registers it. A combined
     * {@code ServiceLoader} over all jars at once (as {@link #fromDirectory} builds for actual
     * serving, right below) can't tell WHICH jar produced a given id. So, per jar, a short-lived
     * child {@link URLClassLoader} (parented to the host, same as the real combined loader) is
     * opened over just that ONE jar's URL, {@code ServiceLoader.load(ProxyPlugin.class, probe)} is
     * run, and every id it yields is recorded against that jar before the probe loader is closed.
     * This never touches the real, long-lived combined registry built afterward; the probe
     * plugins are throwaway, used only for their {@code id()}.
     */
    private static Map<String, Path> probeJarsForProxyIds(File[] jarFiles, URL[] urls) {
        Map<String, Path> jarById = new HashMap<>();
        for (int i = 0; i < jarFiles.length; i++) {
            try (URLClassLoader probe = new URLClassLoader(new URL[] {urls[i]}, ProxyRegistry.class.getClassLoader())) {
                for (ProxyPlugin plugin : ServiceLoader.load(ProxyPlugin.class, probe)) {
                    jarById.put(plugin.id(), jarFiles[i].toPath());
                }
            } catch (IOException e) {
                throw new IllegalStateException("failed to probe proxy jar: " + jarFiles[i], e);
            }
        }
        return jarById;
    }

    /** No proxies directory configured (or none found yet): a valid, zero-proxy state. */
    public static ProxyRegistry empty() {
        return new ProxyRegistry(Collections.emptyList(), null, Collections.emptyMap());
    }

    /** The ids of every discovered proxy, in discovery order. */
    public List<String> listProxyIds() {
        return plugins.stream().map(ProxyPlugin::id).collect(Collectors.toList());
    }

    /** The {@link ProxyPlugin} registering {@code id}, or {@code null} if no such proxy is loaded. */
    public ProxyPlugin pluginFor(String id) {
        for (ProxyPlugin p : plugins) {
            if (p.id().equals(id)) return p;
        }
        return null;
    }

    /** {@code pluginFor(id).profile()}, or {@code null} if no such proxy is loaded. */
    public RoutingProfile profileFor(String id) {
        ProxyPlugin p = pluginFor(id);
        return p != null ? p.profile() : null;
    }

    /** {@code pluginFor(id).displayName()}, or {@code null} if no such proxy is loaded. */
    public String displayNameFor(String id) {
        ProxyPlugin p = pluginFor(id);
        return p != null ? p.displayName() : null;
    }

    /** The jar file that registers {@code id}, or {@code null} if no such proxy is loaded. */
    public Path jarFor(String id) {
        return jars.get(id);
    }

    /**
     * Releases the {@link URLClassLoader} backing this registry's jar-discovered proxies, if
     * any. Safe to call on a registry with no proxy jars (e.g. {@link #empty()}): a no-op in
     * that case. {@code URLClassLoader.close()} is itself safe to call more than once, but
     * callers should only close a registry once nothing still routes through its proxies:
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
