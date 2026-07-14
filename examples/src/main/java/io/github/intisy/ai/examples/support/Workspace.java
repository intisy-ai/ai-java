package io.github.intisy.ai.examples.support;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * A throwaway temp directory the file-backed demos write into, cleaned up on {@link #close()}. Kept
 * separate from the demos so they read as "here is the AiJava usage", not "here is temp-file
 * bookkeeping". Tests use JUnit's {@code @TempDir} instead and never touch this.
 */
public final class Workspace implements Closeable {

    private final Path root;

    private Workspace(Path root) {
        this.root = root;
    }

    public static Workspace create(String prefix) throws IOException {
        return new Workspace(Files.createTempDirectory(prefix));
    }

    public Path root() {
        return root;
    }

    public Path resolve(String name) {
        return root.resolve(name);
    }

    @Override
    public void close() throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort cleanup of a demo scratch dir
                }
            });
        }
    }
}
