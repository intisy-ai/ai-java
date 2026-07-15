package io.github.intisy.ai.exampleserver.discovery;

import io.github.intisy.ai.jvm.backend.json.GsonJsonCodec;
import io.github.intisy.ai.jvm.provider.ProviderRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Resolves the providers directory into a {@link ProviderRegistry}, optionally auto-populating it
 *  from the intisy-ai GitHub org first. Org fetch failures are non-fatal — the server always runs
 *  with whatever providers are already on disk. */
public final class ProviderDiscovery {
    private ProviderDiscovery() {}

    public static ProviderRegistry resolve(Path providersDir, boolean fetchFromOrg) {
        try {
            Files.createDirectories(providersDir);
        } catch (IOException e) {
            // fall through — fromDirectory tolerates a missing/empty dir
        }
        if (fetchFromOrg) {
            try {
                int n = new GithubOrgProviderSource(new GsonJsonCodec()).fetchInto(providersDir);
                System.err.println("[example-server] fetched " + n + " provider jar(s) from intisy-ai");
            } catch (RuntimeException e) {
                System.err.println("[example-server] org provider fetch skipped: " + e.getMessage());
            }
        }
        return ProviderRegistry.fromDirectory(providersDir);
    }
}
