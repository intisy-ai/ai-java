package io.github.intisy.ai.exampleserver.discovery;

import io.github.intisy.ai.jvm.provider.ProviderRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Resolves the providers directory into a {@link ProviderRegistry} from whatever provider jars
 *  are already on disk. Startup never reaches out to the network: downloading a provider jar
 *  from a {@link ProviderSource} is an on-demand action a caller triggers separately (see the
 *  install API), followed by a {@link ProviderRegistryHolder#refresh} to pick it up. */
public final class ProviderDiscovery {
    private ProviderDiscovery() {}

    public static ProviderRegistry resolve(Path providersDir) {
        try {
            Files.createDirectories(providersDir);
        } catch (IOException e) {
            // fall through: fromDirectory tolerates a missing/empty dir
        }
        return ProviderRegistry.fromDirectory(providersDir);
    }
}
