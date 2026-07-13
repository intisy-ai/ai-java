package io.github.intisy.ai.jvm;

import io.github.intisy.ai.shared.spi.Store;

import javax.sql.DataSource;
import java.nio.file.Path;

/**
 * Explicit factory for the JVM {@link Store} backends. This is the ONLY place a caller should
 * reach for a {@link Store} — there is deliberately no "default" method here (e.g. no
 * {@code Storage.defaultStore()}), because {@link AiJava.Builder} treats storage as a REQUIRED
 * choice rather than silently falling back to JSON files. Pick one explicitly:
 * <ul>
 *   <li>{@link #file(Path)} — durable, on-disk, one JSON-string file per key ({@link FileStore});</li>
 *   <li>{@link #memory()} — ephemeral, in-process, no I/O ({@link InMemoryStore}); good for tests
 *       and short-lived processes;</li>
 *   <li>{@link #jdbc(DataSource)} / {@link #jdbc(DataSource, String)} — a real SQL database the
 *       caller already provisioned ({@link JdbcStore}).</li>
 * </ul>
 */
public final class Storage {

    private Storage() {
    }

    /** Durable, nio-backed storage rooted at the given directory. Never guessed — {@code configFolder} is explicit. */
    public static Store file(Path configFolder) {
        return new FileStore(configFolder);
    }

    /** Ephemeral, in-process storage. All state is lost when the process exits. */
    public static Store memory() {
        return new InMemoryStore();
    }

    /** SQL-backed storage against the caller-supplied {@link DataSource}, using the default {@code ai_kv} table. */
    public static Store jdbc(DataSource dataSource) {
        return new JdbcStore(dataSource);
    }

    /** SQL-backed storage against the caller-supplied {@link DataSource} and table name. */
    public static Store jdbc(DataSource dataSource, String table) {
        return new JdbcStore(dataSource, table);
    }
}
