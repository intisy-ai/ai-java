package io.github.intisy.ai.jvm;

import io.github.intisy.ai.shared.spi.Store;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * nio-backed {@link Store}: the real JVM implementation of the key/value JSON-string
 * boundary SPI. A key (e.g. {@code accounts.json}, {@code models.json}, {@code auth.json})
 * is a filename directly under the {@code configFolder} passed to the constructor —
 * the store location is EXPLICIT (this is a server; no CLI-style home-directory sniffing
 * as the primary path). {@link #update} is atomic: mutual exclusion is enforced two ways —
 * a per-key {@link ReentrantLock} serializes threads within this JVM, and a real OS-level
 * {@link FileChannel#lock()} on a per-key {@code .lock} file serializes across processes.
 * The new content is written to a temp file then {@code ATOMIC_MOVE}d into place, so a
 * concurrent reader never observes a partial write.
 *
 * <p>Neither lock ever degrades to running unlocked: both are acquired with blocking calls,
 * so a caller either gets exclusive access or blocks — it never silently loses a write.
 * {@code FileChannel.lock()} is released automatically if the JVM holding it dies, so (unlike
 * the old sentinel-file-{@code createFile} approach this replaced) there is no stale-lock
 * problem to work around.
 *
 * <p>Reference: the old {@code core} module's {@code AccountStore}
 * ({@code core/src/main/java/.../store/AccountStore.java}) {@code withLock}/{@code writeStore}
 * — ported here at the generic per-file level (that class's own {@code providers} JSON
 * document merging now lives in {@code shared}'s {@code AccountStore}, layered on top of
 * this {@link Store}). No {@code LEGACY_FILE} fallback — that migration path is retired.
 */
public class FileStore implements Store {

    // Per-key in-process locks, shared across all FileStore instances so two instances
    // pointing at the same underlying file still serialize correctly within this JVM
    // (a FileChannel lock alone is not reliable for that — see FileChannel.lock() javadoc).
    // Keyed by the absolute lock-file path so different configFolders never collide.
    private static final ConcurrentHashMap<String, ReentrantLock> KEY_LOCKS = new ConcurrentHashMap<>();

    private final Path configFolder;

    public FileStore(Path configFolder) {
        this.configFolder = configFolder;
    }

    /** The explicit base directory this store reads/writes under. */
    public Path configFolder() {
        return configFolder;
    }

    /**
     * Convenience factory: resolves {@code <HUB_CONFIG_DIR>/config} if {@code HUB_CONFIG_DIR}
     * is set, else {@code <user.home>/.ai-java/config}. The env-based dir is a CONVENIENCE,
     * not the primary path — servers should prefer {@link #FileStore(Path)} with an explicit
     * directory.
     */
    public static FileStore fromEnv() {
        return new FileStore(defaultConfigFolder());
    }

    static Path defaultConfigFolder() {
        String forced = System.getenv("HUB_CONFIG_DIR");
        String base = forced != null && !forced.trim().isEmpty()
                ? forced.trim()
                : Paths.get(System.getProperty("user.home"), ".ai-java").toString();
        return Paths.get(base, "config");
    }

    @Override
    public String get(String key) {
        return readRaw(key);
    }

    @Override
    public void put(String key, String value) {
        // Guarded by the same per-key lock as update() so a put() racing an update() on the
        // same key serializes instead of interleaving.
        withLock(key, () -> {
            writeAtomic(key, value);
            return null;
        });
    }

    @Override
    public boolean exists(String key) {
        return Files.exists(fileFor(key));
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(fileFor(key));
        } catch (IOException ignored) {
            // best-effort delete
        }
    }

    @Override
    public void update(String key, UnaryOperator<String> mutator) {
        withLock(key, () -> {
            String next = mutator.apply(readRaw(key));
            writeAtomic(key, next);
            return null;
        });
    }

    @Override
    public List<String> listKeys(String prefix) {
        List<String> keys = new ArrayList<>();
        if (!Files.exists(configFolder)) return keys;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(configFolder)) {
            for (Path p : stream) {
                if (!Files.isRegularFile(p)) continue;
                String name = p.getFileName().toString();
                if (name.startsWith(prefix) && !name.endsWith(".lock") && !name.endsWith(".tmp")) {
                    keys.add(name);
                }
            }
        } catch (IOException ignored) {
            // best-effort listing, mirrors the swallow-all read style elsewhere in this class
        }
        return keys;
    }

    private String readRaw(String key) {
        Path file = fileFor(key);
        try {
            if (!Files.exists(file)) return null;
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null; // best-effort read; degrade to "absent" rather than throw
        }
    }

    private Path fileFor(String key) {
        return configFolder.resolve(key);
    }

    private void ensureDir() {
        try {
            if (!Files.exists(configFolder)) Files.createDirectories(configFolder);
        } catch (IOException ignored) {
            // best-effort, mirrors the old AccountStore's unguarded-mkdir-only-failure-point note
        }
    }

    private void writeAtomic(String key, String value) {
        ensureDir();
        Path file = fileFor(key);
        Path tmp = file.resolveSibling(file.getFileName().toString() + "." + randomHex(6) + ".tmp");
        try {
            Files.write(tmp, (value != null ? value : "").getBytes(StandardCharsets.UTF_8));
            try {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException | IOException ignored) {
                // POSIX permissions aren't supported on this filesystem (e.g. Windows); not fatal
            }
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("failed to write store file " + file, e);
        }
    }

    /**
     * Exclusive per-key lock — never degrades to running unlocked. Two layers, both acquired
     * with blocking calls:
     * <ol>
     *   <li>a {@link ReentrantLock} keyed by this file's absolute path, serializing threads
     *       within this JVM (this is what makes the intra-JVM concurrency test deterministic);</li>
     *   <li>inside that, a real OS-level {@link FileChannel#lock()} on the per-key {@code .lock}
     *       file, serializing across processes. It blocks until acquired; if that's ever not
     *       viable, the {@link IOException} propagates rather than silently proceeding unlocked.</li>
     * </ol>
     */
    private <T> T withLock(String key, Supplier<T> fn) {
        ensureDir();
        Path lockPath = fileFor(key + ".lock");
        ReentrantLock jvmLock = KEY_LOCKS.computeIfAbsent(lockPath.toAbsolutePath().toString(), k -> new ReentrantLock());
        jvmLock.lock();
        try (FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            try (FileLock osLock = channel.lock()) {
                return fn.get();
            }
        } catch (IOException e) {
            throw new RuntimeException("failed to acquire cross-process lock for " + key, e);
        } finally {
            jvmLock.unlock();
        }
    }

    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
