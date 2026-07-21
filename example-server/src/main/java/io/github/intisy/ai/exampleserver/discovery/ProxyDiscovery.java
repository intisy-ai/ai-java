package io.github.intisy.ai.exampleserver.discovery;

import io.github.intisy.ai.jvm.proxy.ProxyRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Resolves the proxies directory into a {@link ProxyRegistry} from whatever proxy jars are
 *  already on disk. Startup never reaches out to the network: downloading a proxy jar from a
 *  {@link ProxySource} is an on-demand action a caller triggers separately (see the install
 *  API), followed by a {@link ProxyRegistryHolder#refresh} to pick it up. */
public final class ProxyDiscovery {
    private ProxyDiscovery() {}

    public static ProxyRegistry resolve(Path proxiesDir) {
        try {
            Files.createDirectories(proxiesDir);
        } catch (IOException e) {
            // fall through: fromDirectory tolerates a missing/empty dir
        }
        return ProxyRegistry.fromDirectory(proxiesDir);
    }
}
