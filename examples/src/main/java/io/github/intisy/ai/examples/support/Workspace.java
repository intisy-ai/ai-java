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

    /**
     * A temp directory that deletes itself, so a demo never writes into a real home.
     *
     * @param prefix the temp directory's name prefix
     * @return the workspace, which must be closed
     * @throws IOException when the directory cannot be created
     */
    public static Workspace create(String prefix) throws IOException {
        return new Workspace(Files.createTempDirectory(prefix));
    }

    /** {@return the workspace's own directory} */
    public Path root() {
        return root;
    }

    /**
     * {@return one path inside the workspace}
     *
     * @param name the entry to resolve
     */
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
