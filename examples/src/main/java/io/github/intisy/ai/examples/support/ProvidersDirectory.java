package io.github.intisy.ai.examples.support;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Locates the directory the {@code :examples-provider} jar is staged into. The Gradle {@code run}
 * and {@code test} tasks build that jar, copy it here, and pass this path in the
 * {@code examples.providersDir} system property (see {@code examples/build.gradle}). This class just
 * reads that property, with a conventional fallback so the demos still explain themselves when run
 * straight from an IDE.
 */
public final class ProvidersDirectory {

    public static final String PROPERTY = "examples.providersDir";

    private ProvidersDirectory() {
    }

    /** The staged providers directory, or {@code null} if it cannot be found. */
    public static Path locate() {
        String configured = System.getProperty(PROPERTY);
        if (configured != null && !configured.isEmpty()) {
            Path path = Paths.get(configured);
            if (Files.isDirectory(path)) return path;
        }
        // Fallbacks for running outside the Gradle tasks (e.g. from an IDE after a `gradle build`).
        for (Path candidate : new Path[] {
                Paths.get("build", "providers"),
                Paths.get("examples", "build", "providers")}) {
            if (Files.isDirectory(candidate)) return candidate;
        }
        return null;
    }
}
