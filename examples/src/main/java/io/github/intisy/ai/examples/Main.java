package io.github.intisy.ai.examples;

import io.github.intisy.ai.examples.support.ProvidersDirectory;

import java.nio.file.Path;

/**
 * Runs every demo in order, top to bottom — read this file first, then follow each demo class. A
 * newcomer embedding ai-java in a server can trace, in one run: choosing a storage backend, swapping
 * every platform SPI, discovering provider jars, routing (fallback / rewrite / exhaustion /
 * {@code /v1/models}), managing accounts (acquire / cooldown / backoff / refresh / revoke), and the
 * default notification path.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("ai-java :examples — full :jvm API walkthrough");

        Path providersDir = ProvidersDirectory.locate();
        if (providersDir == null) {
            throw new IllegalStateException(
                    "provider jar directory not found. Run via `gradlew :examples:run` (it stages "
                            + ":examples-provider's jar and sets the '" + ProvidersDirectory.PROPERTY + "' property).");
        }

        StorageDemo.run();
        CustomSpiDemo.run();
        ProviderRegistryDemo.run(providersDir);
        RoutingDemo.run(providersDir);
        AccountManagerDemo.run();
        NotifierDemo.run();

        System.out.println();
        System.out.println("All demos completed.");
    }
}
