package io.github.intisy.ai.jvm.backend.store;

import io.github.intisy.ai.shared.spi.Store;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * JDBC-backed {@link Store}: a SQL blob key/value table for servers running against a real
 * database (H2, MySQL, PostgreSQL, SQLite) instead of {@link FileStore}'s JSON files. The
 * library itself has no runtime JDBC driver dependency: the caller (server) supplies a
 * configured {@link DataSource}; only {@code javax.sql} types are used here, which are part of
 * the Java 8 platform.
 *
 * <p><b>Schema.</b> The table ({@code ai_kv} by default) is auto-created on construction with
 * a portable {@code CREATE TABLE IF NOT EXISTS} (supported by H2, MySQL, PostgreSQL, and
 * SQLite): {@code (k VARCHAR(512) PRIMARY KEY, v <text-type>)}. The value column's type is
 * chosen from {@link java.sql.DatabaseMetaData#getDatabaseProductName()} because {@code CLOB}
 * is not a native MySQL/PostgreSQL type: H2 and SQLite get the literal {@code CLOB} (SQLite's
 * dynamic typing accepts it fine), MySQL/MariaDB get {@code LONGTEXT}, PostgreSQL gets
 * {@code TEXT}. Any other/unrecognized database falls back to {@code CLOB}, the ANSI SQL
 * large-object type.
 *
 * <p><b>Atomic {@code update}: ensure-row-then-lock.</b> {@code SELECT ... FOR UPDATE} only
 * locks a row that already exists; on an absent key it locks nothing, so two concurrent
 * {@code update} calls on the SAME absent key could both read {@code null} and one's
 * {@code UPDATE} would silently clobber the other's already-committed {@code INSERT}: a lost
 * write with no exception. To make the lock always cover the eventual write, {@code update}
 * first <b>ensures the row exists</b> ({@code INSERT ... (k, v) VALUES (?, NULL)}, swallowing
 * the duplicate-key {@code SQLException} if another transaction just did the same insert;
 * a portable, DB-agnostic "insert-if-absent"), THEN runs
 * {@code SELECT v FROM ai_kv WHERE k = ? FOR UPDATE}, except on SQLite, which has no
 * {@code FOR UPDATE} syntax at all and doesn't need one: it has no row-level locking, and the
 * {@code ensureRowExists} INSERT just above already takes SQLite's whole-database write lock
 * for the rest of the transaction, so the plain {@code SELECT} that follows is just as
 * serialized (see {@link #supportsRowLocking}). A real row is now always locked (or, on
 * SQLite, the whole database already is), so a second {@code update} on the same key
 * (whether it started out present or absent) blocks until the first transaction commits,
 * deterministically, with no retry needed for this race.
 *
 * <p><b>NULL {@code v} means logically absent.</b> The placeholder row inserted by
 * ensure-row-then-lock has {@code v = NULL}; reading it back must not be mistaken for a real
 * empty-string value. So a row with {@code v IS NULL} is treated the same as "no row" by every
 * method: {@link #get} returns {@code null}, {@link #exists} returns {@code false},
 * {@link #listKeys} excludes it, and inside {@code update}/{@code put} the mutator sees
 * {@code current == null} exactly as it would for a genuinely absent key.
 *
 * <p><b>{@code put} shares {@code update}'s atomic path.</b> A separate UPDATE-then-INSERT
 * upsert for {@code put} would have the exact same absent-key race described above (two
 * concurrent {@code put}s on a fresh key can both miss the {@code UPDATE} and collide on
 * {@code INSERT}). Rather than duplicate the ensure-row-then-lock logic, {@code put(key, value)}
 * is simply {@code update(key, ignored -> value)}.
 *
 * <p><b>Failure handling.</b> The whole read-mutate-write cycle runs in one transaction
 * (autoCommit off) and is wrapped so that ANY throwable (a JDBC failure or a {@code
 * RuntimeException} thrown by the caller's mutator) triggers a rollback before propagating,
 * so a failure never leaves an open transaction on a pooled {@link DataSource}. A bounded retry
 * ({@link #MAX_ATTEMPTS}) is kept only for genuinely transient SQL failures (deadlock /
 * serialization-failure SQLStates); the absent-key race is handled deterministically by
 * ensure-row-then-lock and doesn't need a retry.
 *
 * <p>Semantics match {@link FileStore}: {@link #get} of an absent key returns {@code null},
 * {@link #delete} of an absent key is a no-op, a {@code null} mutator result still creates/keeps
 * the key written as an empty string (never {@code null}, so it doesn't look absent), and
 * {@link #listKeys} returns keys starting with {@code prefix} (a literal prefix: {@code %}/
 * {@code _} in it are escaped, not treated as SQL wildcards).
 */
public class JdbcStore implements Store {

    private static final String DEFAULT_TABLE = "ai_kv";
    // SQLite's own busy-timeout (its default is 0ms - a second writer fails IMMEDIATELY with
    // SQLITE_BUSY instead of waiting) - since SQLite serializes all writers on one whole-database
    // lock (see supportsRowLocking), this timeout is what makes concurrent update()/put() calls
    // wait for each other rather than error out, mirroring what FOR UPDATE gives H2/MySQL/Postgres.
    private static final int SQLITE_BUSY_TIMEOUT_MS = 30_000;
    // SQLite's own numeric result code for SQLITE_CONSTRAINT (any constraint violation) - used
    // by isDuplicateKey; see its javadoc for why this doesn't create a driver dependency.
    private static final int SQLITE_CONSTRAINT_ERROR_CODE = 19;
    // bounds the retry loop in update() for genuinely transient failures (deadlock /
    // serialization-failure SQLStates); each attempt is a full transaction, so this is not a
    // busy spin. The absent-key race is handled deterministically by ensure-row-then-lock and
    // does not rely on this retry.
    private static final int MAX_ATTEMPTS = 5;

    private final DataSource dataSource;
    private final String table;
    // SQLite has no "SELECT ... FOR UPDATE" syntax (it throws a syntax error) and doesn't need
    // one: unlike H2/MySQL/PostgreSQL it has no row-level locking at all - a writer transaction
    // already takes SQLite's whole-database RESERVED/EXCLUSIVE lock on its first write statement
    // (ensureRowExists' INSERT, right before selectForUpdate runs), so omitting the clause loses
    // no atomicity there. Detected once at construction, same pattern as valueColumnType.
    private final boolean supportsRowLocking;

    public JdbcStore(DataSource dataSource) {
        this(dataSource, DEFAULT_TABLE);
    }

    public JdbcStore(DataSource dataSource, String table) {
        this.dataSource = dataSource;
        this.table = validateTableName(table);
        this.supportsRowLocking = createTableIfAbsent();
    }

    // Central connection acquisition point: on SQLite, applies the busy-timeout PRAGMA (see
    // SQLITE_BUSY_TIMEOUT_MS) so a connection that finds the database locked by another writer
    // waits instead of failing immediately. A no-op on every other database.
    private Connection connect() throws SQLException {
        Connection conn = dataSource.getConnection();
        if (!supportsRowLocking) {
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA busy_timeout = " + SQLITE_BUSY_TIMEOUT_MS);
            }
        }
        return conn;
    }

    @Override
    public String get(String key) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("SELECT v FROM " + table + " WHERE k = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                // getString(...) returns Java null for a SQL NULL v, which is exactly the
                // "absent" convention we want for a NULL-v placeholder row too.
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("failed to read key " + key, e);
        }
    }

    // put shares update()'s ensure-row-then-lock path so it can't race the same way a
    // standalone UPDATE-then-INSERT upsert would on a freshly-absent key (see class javadoc).
    @Override
    public void put(String key, String value) {
        update(key, ignored -> value);
    }

    @Override
    public boolean exists(String key) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM " + table + " WHERE k = ? AND v IS NOT NULL")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException("failed to check key " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM " + table + " WHERE k = ?")) {
            ps.setString(1, key);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("failed to delete key " + key, e);
        }
    }

    @Override
    public void update(String key, UnaryOperator<String> mutator) {
        SQLException lastError = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try (Connection conn = connect()) {
                conn.setAutoCommit(false);
                try {
                    // ensure-row-then-lock: guarantees there is always a real row for the
                    // FOR UPDATE below to lock, even when the key has never been written.
                    ensureRowExists(conn, key);
                    String current = selectForUpdate(conn, key);
                    String next = mutator.apply(current);
                    // mirror FileStore/InMemoryStore: a null mutator result still creates/keeps
                    // the key, written as an empty string, rather than looking absent.
                    setValue(conn, key, next != null ? next : "");
                    conn.commit();
                    return;
                } catch (Throwable t) {
                    // roll back on ANY throwable, including a RuntimeException from the
                    // caller's mutator - never leave an open transaction on a pooled connection.
                    safeRollback(conn);
                    if (t instanceof SQLException && attempt < MAX_ATTEMPTS - 1
                            && isTransient((SQLException) t)) {
                        lastError = (SQLException) t;
                        continue;
                    }
                    if (t instanceof RuntimeException) throw (RuntimeException) t;
                    if (t instanceof Error) throw (Error) t;
                    throw new RuntimeException("failed to update key " + key, t);
                } finally {
                    try {
                        conn.setAutoCommit(true);
                    } catch (SQLException ignored) {
                        // best-effort restore; the connection is about to be closed/returned
                        // to the pool either way.
                    }
                }
            } catch (SQLException e) {
                // failed to even obtain a connection / flip autoCommit for this attempt
                lastError = e;
            }
        }
        throw new RuntimeException(
                "failed to update key " + key + " after " + MAX_ATTEMPTS + " attempts", lastError);
    }

    @Override
    public List<String> listKeys(String prefix) {
        List<String> keys = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT k FROM " + table + " WHERE k LIKE ? ESCAPE '\\' AND v IS NOT NULL")) {
            ps.setString(1, escapeLikePattern(prefix) + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) keys.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new RuntimeException("failed to list keys with prefix " + prefix, e);
        }
        return keys;
    }

    // Portable "insert-if-absent": always leaves a row behind for the FOR UPDATE select that
    // follows to lock, even if the key has never been written. A duplicate-key SQLException
    // (another transaction already has - or just inserted - this row) is swallowed: that's
    // exactly the outcome we want, we just need SOME row to exist and be lockable.
    private void ensureRowExists(Connection conn, String key) throws SQLException {
        try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO " + table + " (k, v) VALUES (?, NULL)")) {
            insert.setString(1, key);
            insert.executeUpdate();
        } catch (SQLException e) {
            if (!isDuplicateKey(e)) throw e;
            // row already exists - fine, ensureRowExists only needed a row to exist at all.
        }
    }

    private String selectForUpdate(Connection conn, String key) throws SQLException {
        String sql = "SELECT v FROM " + table + " WHERE k = ?" + (supportsRowLocking ? " FOR UPDATE" : "");
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                // getString(...) maps SQL NULL to Java null - our placeholder row's NULL v
                // and a (defensive, shouldn't happen after ensureRowExists) missing row both
                // read as "absent" for the mutator, matching FileStore's convention.
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private void setValue(Connection conn, String key, String value) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE " + table + " SET v = ? WHERE k = ?")) {
            ps.setString(1, value);
            ps.setString(2, key);
            ps.executeUpdate();
        }
    }

    private static void safeRollback(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
            // best-effort; the original throwable is what the caller needs to see.
        }
    }

    // SQLState class "23" (Integrity Constraint Violation) covers primary-key/unique
    // violations on H2, MySQL, and PostgreSQL alike - portable without vendor-specific
    // error-code checks.
    private static boolean isDuplicateKey(SQLException e) {
        String state = e.getSQLState();
        if (state != null && state.startsWith("23")) return true;
        // SQLite's driver leaves getSQLState() null for constraint violations and reports its
        // own numeric result code instead (19 = SQLITE_CONSTRAINT, incl. the primary-key/unique
        // variants) - checked as a plain int, not an org.sqlite.* type, so :jvm still has no
        // compile-time dependency on the SQLite driver. ensureRowExists's INSERT only ever
        // touches (k, v) with the sole constraint being k's PRIMARY KEY, so any SQLITE_CONSTRAINT
        // here can only be that duplicate-key case.
        return state == null && e.getErrorCode() == SQLITE_CONSTRAINT_ERROR_CODE;
    }

    // SQLState class "40" (Transaction Rollback, e.g. 40001 serialization failure / 40P01
    // Postgres deadlock) plus "41000" (lock timeout, used by some MySQL drivers for deadlocks)
    // are genuinely transient - worth a bounded retry. Anything else fails fast.
    private static boolean isTransient(SQLException e) {
        String state = e.getSQLState();
        return state != null && (state.startsWith("40") || state.equals("41000"));
    }

    // Returns whether the underlying database supports SELECT ... FOR UPDATE (see the
    // supportsRowLocking field javadoc for why SQLite doesn't need it).
    private boolean createTableIfAbsent() {
        try (Connection conn = dataSource.getConnection()) {
            String product = conn.getMetaData().getDatabaseProductName();
            String valueType = valueColumnType(product);
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS " + table
                        + " (k VARCHAR(512) PRIMARY KEY, v " + valueType + ")");
            }
            return product == null || !product.toLowerCase(Locale.ROOT).contains("sqlite");
        } catch (SQLException e) {
            throw new RuntimeException("failed to create store table " + table, e);
        }
    }

    // See class javadoc: CLOB isn't native on MySQL/PostgreSQL, so pick the equivalent
    // large-text type per database product rather than hardcoding one dialect. SQLite has no
    // real column typing (types are dynamic/advisory) and accepts CLOB fine, so it falls into
    // the same default branch as H2.
    private static String valueColumnType(String product) {
        if (product == null) return "CLOB";
        String p = product.toLowerCase(Locale.ROOT);
        if (p.contains("mysql") || p.contains("mariadb")) return "LONGTEXT";
        if (p.contains("postgresql")) return "TEXT";
        return "CLOB"; // H2, SQLite, and other CLOB-tolerant databases
    }

    private static String escapeLikePattern(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    // table is concatenated directly into SQL (JDBC has no parameter placeholder for
    // identifiers), so restrict it to a safe identifier shape rather than risking injection
    // through a caller-supplied table name.
    private static String validateTableName(String table) {
        if (table == null || !table.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("invalid table name: " + table);
        }
        return table;
    }
}
