package io.github.intisy.ai.jvm;

import io.github.intisy.ai.shared.spi.Store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * nio-backed {@link Store}: the real JVM implementation of the key/value JSON-string
 * boundary SPI. A key (e.g. {@code accounts.json}, {@code models.json}, {@code auth.json})
 * is a filename directly under the {@code configFolder} passed to the constructor —
 * the store location is EXPLICIT (this is a server; no CLI-style home-directory sniffing
 * as the primary path). {@link #update} is atomic: a cross-process {@code .lock} file
 * guards a read-modify-write, and the new content is written to a temp file then
 * {@code ATOMIC_MOVE}d into place, so a concurrent reader never observes a partial write.
 *
 * <p>Reference: the old {@code core} module's {@code AccountStore}
 * ({@code core/src/main/java/.../store/AccountStore.java}) {@code withLock}/{@code writeStore}
 * — ported here at the generic per-file level (that class's own {@code providers} JSON
 * document merging now lives in {@code shared}'s {@code AccountStore}, layered on top of
 * this {@link Store}). No {@code LEGACY_FILE} fallback — that migration path is retired.
 */
public class FileStore implements Store {

    private static final long LOCK_STALE_MS = 15_000;
    private static final long LOCK_WAIT_MS = 5_000;
    private static final long LOCK_POLL_MS = 25;

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
        ensureDir();
        writeAtomic(key, value);
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

    // best-effort exclusive per-key lock; degrades to running unlocked rather than deadlocking
    // if the lock can't be acquired (mirrors the old AccountStore.withLock).
    private <T> T withLock(String key, Supplier<T> fn) {
        ensureDir();
        Path lockPath = fileFor(key + ".lock");
        long deadline = System.currentTimeMillis() + LOCK_WAIT_MS;
        boolean acquired = false;
        while (!acquired) {
            try {
                Files.createFile(lockPath);
                acquired = true;
            } catch (FileAlreadyExistsException e) {
                try {
                    FileTime mtime = Files.getLastModifiedTime(lockPath);
                    if (System.currentTimeMillis() - mtime.toMillis() > LOCK_STALE_MS) {
                        Files.deleteIfExists(lockPath);
                        continue;
                    }
                } catch (IOException ignored) {
                    // stale-check is best-effort; fall through to the wait/deadline check below
                }
                if (System.currentTimeMillis() > deadline) break;
                try {
                    Thread.sleep(LOCK_POLL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } catch (IOException e) {
                break; // can't create the lock file at all; degrade to unlocked
            }
        }
        try {
            return fn.get();
        } finally {
            if (acquired) {
                try {
                    Files.deleteIfExists(lockPath);
                } catch (IOException ignored) {
                }
            }
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
