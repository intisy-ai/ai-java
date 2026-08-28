package io.github.intisy.ai.jvm;

import io.github.intisy.ai.seam.jvm.FileStore;
import io.github.intisy.ai.seam.InMemoryStore;
import io.github.intisy.ai.seam.jvm.JdbcStore;
import io.github.intisy.ai.api.seam.Store;

import javax.sql.DataSource;
import java.nio.file.Path;

/**
 * Explicit factory for the JVM {@link Store} backends. This is the ONLY place a caller should
 * reach for a {@link Store}: there is deliberately no "default" method here (e.g. no
 * {@code Storage.defaultStore()}), because {@link AiJava.Builder} treats storage as a REQUIRED
 * choice rather than silently falling back to JSON files. Pick one explicitly:
 * <ul>
 *   <li>{@link #file(Path)}: durable, on-disk, one JSON-string file per key ({@link FileStore});</li>
 *   <li>{@link #memory()}: ephemeral, in-process, no I/O ({@link InMemoryStore}); good for tests
 *       and short-lived processes;</li>
 *   <li>{@link #jdbc(DataSource)} / {@link #jdbc(DataSource, String)}: a real SQL database the
 *       caller already provisioned ({@link JdbcStore}).</li>
 * </ul>
 */
public final class Storage {

    private Storage() {
    }

    /**
     * Durable, nio-backed storage rooted at the given directory.
     *
     * @param configFolder the root, which is always explicit and never guessed
     * @return storage writing one JSON-string file per key under that root
     */
    public static Store file(Path configFolder) {
        return new FileStore(configFolder);
    }

    /** {@return ephemeral, in-process storage, whose state is lost when the process exits} */
    public static Store memory() {
        return new InMemoryStore();
    }

    /**
     * SQL-backed storage using the default {@code ai_kv} table.
     *
     * @param dataSource a database the caller already provisioned
     * @return storage reading and writing that database
     */
    public static Store jdbc(DataSource dataSource) {
        return new JdbcStore(dataSource);
    }

    /**
     * SQL-backed storage against a named table.
     *
     * @param dataSource a database the caller already provisioned
     * @param table the table to keep keys and values in
     * @return storage reading and writing that table
     */
    public static Store jdbc(DataSource dataSource, String table) {
        return new JdbcStore(dataSource, table);
    }
}
