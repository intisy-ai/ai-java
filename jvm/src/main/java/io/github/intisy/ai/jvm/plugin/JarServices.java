package io.github.intisy.ai.jvm.plugin;

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

/**
 * Runtime discovery of one service-provider interface from a directory of jars.
 *
 * @implNote The SPI is a parameter rather than a fixed type, because the scan, the class loading
 * and the {@code ServiceLoader} call are identical whatever is being discovered. Three registries
 * once carried a copy of this each, which is three places for the class-loader discipline below to
 * drift apart in.
 *
 * <p>The loader is parented to THIS class's own loader, so a jar's implementation resolves the
 * shared interfaces to the same classes the host already has loaded rather than to a second,
 * incompatible copy that {@code ServiceLoader} would silently fail to cast.
 *
 * <p>It is deliberately not closed when the scan finishes. Closing releases the loader's open jar
 * handles, which is what lets a jar be replaced on Windows, but it does NOT unload the classes it
 * already defined, and it DOES make a later {@code defineClass} fail: a helper type, a custom
 * exception on an error branch or a lazily-initialised enum that a jar references only from a
 * method body nobody has run yet then throws {@link NoClassDefFoundError} the moment that path
 * finally executes. So a caller closes only once nothing still holds one of these services.
 *
 * @param <T> the service-provider interface being discovered
 */
public final class JarServices<T> implements Closeable {

    /**
     * What identifies one discovered service, for an SPI that has a notion of identity.
     *
     * @param <T> the service-provider interface
     */
    public interface Ids<T> {
        /**
         * The id one service registers itself under.
         *
         * @param service the discovered service
         * @return its id
         */
        String idOf(T service);
    }

    private final List<T> services;
    private final URLClassLoader classLoader; // null when no jars were found
    private final Map<String, Path> jars; // service id -> the jar file that registers it

    private JarServices(List<T> services, URLClassLoader classLoader, Map<String, Path> jars) {
        this.services = Collections.unmodifiableList(services);
        this.classLoader = classLoader;
        this.jars = jars;
    }

    /**
     * Scans a directory for jars and discovers every implementation of one SPI they register.
     *
     * @implNote A missing or empty directory yields an empty result rather than an error: nothing
     * installed is a valid, common state, and a fresh install is exactly that.
     *
     * @param directory the directory to scan
     * @param spi the service-provider interface to discover
     * @param ids what identifies a discovered service, or null for an SPI with no identity
     * @param <T> the service-provider interface
     * @return the discovered services, empty when the directory holds no jars
     */
    public static <T> JarServices<T> fromDirectory(Path directory, Class<T> spi, Ids<T> ids) {
        File dir = directory.toFile();
        File[] jarFiles = dir.isDirectory() ? dir.listFiles((d, name) -> name.endsWith(".jar")) : null;
        if (jarFiles == null || jarFiles.length == 0) {
            return empty();
        }

        URL[] urls = new URL[jarFiles.length];
        for (int i = 0; i < jarFiles.length; i++) {
            try {
                urls[i] = jarFiles[i].toURI().toURL();
            } catch (MalformedURLException failure) {
                throw new IllegalStateException("unreadable " + spi.getSimpleName() + " jar path: " + jarFiles[i], failure);
            }
        }

        Map<String, Path> jarById = ids == null
                ? Collections.<String, Path>emptyMap()
                : probeJarsForIds(jarFiles, urls, spi, ids);

        URLClassLoader classLoader = new URLClassLoader(urls, JarServices.class.getClassLoader());
        List<T> loaded = new ArrayList<T>();
        for (T service : ServiceLoader.load(spi, classLoader)) {
            loaded.add(service);
        }
        return new JarServices<T>(loaded, classLoader, jarById);
    }

    /**
     * Attributes each discovered id to the single jar that registers it.
     *
     * @implNote One {@code ServiceLoader} over every jar at once cannot say WHICH jar produced a
     * given id, so each jar gets a short-lived probe loader of its own, parented to the host the
     * same way the real one is. The probe services are throwaway, read only for their id, and the
     * real long-lived loader is built separately afterwards.
     */
    private static <T> Map<String, Path> probeJarsForIds(File[] jarFiles, URL[] urls, Class<T> spi, Ids<T> ids) {
        Map<String, Path> jarById = new HashMap<String, Path>();
        for (int i = 0; i < jarFiles.length; i++) {
            URLClassLoader probe = new URLClassLoader(new URL[] {urls[i]}, JarServices.class.getClassLoader());
            try {
                for (T service : ServiceLoader.load(spi, probe)) {
                    jarById.put(ids.idOf(service), jarFiles[i].toPath());
                }
            } finally {
                try {
                    probe.close();
                } catch (IOException failure) {
                    throw new IllegalStateException("failed to probe " + spi.getSimpleName() + " jar: " + jarFiles[i], failure);
                }
            }
        }
        return jarById;
    }

    /**
     * Nothing discovered, which is the valid state before any jar is installed.
     *
     * @param <T> the service-provider interface
     * @return an empty result whose {@link #close} is a no-op
     */
    public static <T> JarServices<T> empty() {
        return new JarServices<T>(Collections.<T>emptyList(), null, Collections.<String, Path>emptyMap());
    }

    /**
     * Every discovered service.
     *
     * @return them in discovery order
     */
    public List<T> all() {
        return services;
    }

    /**
     * The jar that registers one id.
     *
     * @param id the service id to look for
     * @return its jar, or null when nothing registers that id
     */
    public Path jarFor(String id) {
        return jars.get(id);
    }

    /**
     * Releases the class loader backing the discovered services, if there is one.
     *
     * @throws IOException when the loader cannot be released
     */
    @Override
    public void close() throws IOException {
        if (classLoader != null) {
            classLoader.close();
        }
    }
}
