package io.github.intisy.ai.jvm.proxy;

import io.github.intisy.ai.jvm.plugin.JarServices;
import io.github.intisy.ai.shared.routing.ProxyPlugin;
import io.github.intisy.ai.shared.routing.RoutingProfile;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Runtime {@link ProxyPlugin} discovery: scans a directory for {@code *.jar} files, loads them on
 * a dedicated class loader parented to the host (so a jar's {@code ProxyPlugin} implementation sees
 * the exact same {@code ProxyPlugin}/{@code RoutingProfile} classes as the host, avoiding a
 * classloader-identity mismatch), and discovers implementations via
 * {@code ServiceLoader.load(ProxyPlugin.class, classLoader)}. A proxy jar registers itself the
 * usual JVM way: {@code META-INF/services/io.github.intisy.ai.shared.routing.ProxyPlugin} listing
 * its implementation class(es).
 *
 * <p>This is the proxy-side mirror of {@code ProviderRegistry}: same classloader discipline, same
 * rationale, keyed by {@link ProxyPlugin#id()} instead of a provider id.
 *
 * <p>The scan, the class loading and the discovery itself live in {@link JarServices}, which also
 * documents when a registry may be closed and what closing one costs.
 */
public final class ProxyRegistry implements Closeable {

    private final JarServices<ProxyPlugin> discovered;

    private ProxyRegistry(JarServices<ProxyPlugin> discovered) {
        this.discovered = discovered;
    }

    /**
     * Scans {@code proxiesDir} for {@code *.jar} files and discovers every {@link ProxyPlugin}
     * they register via {@code ServiceLoader}. A missing or empty directory yields an empty
     * registry (not an error): zero proxies installed is a valid, common state (e.g. a
     * fresh install before any proxy jar has been dropped in).
     *
     * @param proxiesDir the directory to scan
     * @return the registry, empty when the directory holds no jars
     */
    public static ProxyRegistry fromDirectory(Path proxiesDir) {
        return new ProxyRegistry(JarServices.fromDirectory(proxiesDir, ProxyPlugin.class, ProxyPlugin::id));
    }

    /** {@return a registry with no proxies, the valid state before any jar is installed} */
    public static ProxyRegistry empty() {
        return new ProxyRegistry(JarServices.<ProxyPlugin>empty());
    }

    /** {@return the id of every discovered proxy, in discovery order} */
    public List<String> listProxyIds() {
        return discovered.all().stream().map(ProxyPlugin::id).collect(Collectors.toList());
    }

    /**
     * {@return the plugin registering this id, or {@code null} when none is loaded}
     *
     * @param id the proxy id to look for
     */
    public ProxyPlugin pluginFor(String id) {
        for (ProxyPlugin p : discovered.all()) {
            if (p.id().equals(id)) return p;
        }
        return null;
    }

    /**
     * {@return that proxy's routing profile, or {@code null} when none is loaded}
     *
     * @param id the proxy id to look for
     */
    public RoutingProfile profileFor(String id) {
        ProxyPlugin p = pluginFor(id);
        return p != null ? p.profile() : null;
    }

    /**
     * {@return that proxy's display name, or {@code null} when none is loaded}
     *
     * @param id the proxy id to look for
     */
    public String displayNameFor(String id) {
        ProxyPlugin p = pluginFor(id);
        return p != null ? p.displayName() : null;
    }

    /**
     * {@return the jar registering this proxy, or {@code null} when it is not loaded}
     *
     * @param id the proxy whose jar is wanted
     */
    public Path jarFor(String id) {
        return discovered.jarFor(id);
    }

    /**
     * Releases the class loader backing this registry's jar-discovered proxies, if any. See
     * {@link JarServices#close} for when that is safe.
     *
     * @throws IOException when the loader cannot be released
     */
    @Override
    public void close() throws IOException {
        discovered.close();
    }
}
