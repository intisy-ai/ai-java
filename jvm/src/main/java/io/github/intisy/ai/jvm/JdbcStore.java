package io.github.intisy.ai.jvm;

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
 * database (H2, MySQL, PostgreSQL) instead of {@link FileStore}'s JSON files. The library
 * itself has no runtime JDBC driver dependency — the caller (server) supplies a configured
 * {@link DataSource}; only {@code javax.sql} types are used here, which are part of the
 * Java 8 platform.
 *
 * <p><b>Schema.</b> The table ({@code ai_kv} by default) is auto-created on construction with
 * a portable {@code CREATE TABLE IF NOT EXISTS} (supported by H2, MySQL, and PostgreSQL):
 * {@code (k VARCHAR(512) PRIMARY KEY, v <text-type>)}. The value column's type is chosen from
 * {@link java.sql.DatabaseMetaData#getDatabaseProductName()} because {@code CLOB} is not a
 * native MySQL/PostgreSQL type: H2 gets the literal {@code CLOB}, MySQL/MariaDB get
 * {@code LONGTEXT}, PostgreSQL gets {@code TEXT}. Any other/unrecognized database falls back
 * to {@code CLOB}, the ANSI SQL large-object type.
 *
 * <p><b>Upsert.</b> {@code put} (and the write half of {@code update}) uses a portable
 * UPDATE-then-INSERT upsert run inside one transaction, rather than a vendor-specific
 * {@code MERGE} (H2) / {@code ON DUPLICATE KEY UPDATE} (MySQL) / {@code ON CONFLICT} (Postgres)
 * statement: try the {@code UPDATE} first, and only {@code INSERT} if no row was updated. This
 * needs no per-vendor SQL branching and works unchanged on all three databases.
 *
 * <p><b>Atomic {@code update}.</b> Runs the whole read-mutate-upsert cycle inside a single
 * transaction (autoCommit off): {@code SELECT v FROM ai_kv WHERE k = ? FOR UPDATE} row-locks
 * the key (H2, MySQL/InnoDB, and PostgreSQL all honor {@code FOR UPDATE}) for the rest of the
 * transaction, so a second {@code update} on the SAME key blocks until the first commits —
 * no lost update. {@code FOR UPDATE} locks an existing row, not a not-yet-existing one, so a
 * race between two {@code update} calls both creating the SAME absent key can still collide on
 * the {@code INSERT}'s primary key; that case is handled by retrying the whole cycle (bounded
 * by {@link #MAX_ATTEMPTS}) rather than losing the write. {@code put}/{@code delete}/{@code get}
 * don't need that row lock since they aren't a read-then-conditionally-write cycle.
 *
 * <p>Semantics match {@link FileStore}: {@link #get} of an absent key returns {@code null},
 * {@link #delete} of an absent key is a no-op, {@link #listKeys} returns keys starting with
 * {@code prefix} (a literal prefix — {@code %}/{@code _} in it are escaped, not treated as SQL
 * wildcards).
 */
public class JdbcStore implements Store {

    private static final String DEFAULT_TABLE = "ai_kv";
    // bounds the retry loop in update() for the absent-key insert/insert race described above;
    // each attempt is a full transaction, so this is not a busy spin.
    private static final int MAX_ATTEMPTS = 5;

    private final DataSource dataSource;
    private final String table;

    public JdbcStore(DataSource dataSource) {
        this(dataSource, DEFAULT_TABLE);
    }

    public JdbcStore(DataSource dataSource, String table) {
        this.dataSource = dataSource;
        this.table = validateTableName(table);
        createTableIfAbsent();
    }

    @Override
    public String get(String key) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT v FROM " + table + " WHERE k = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("failed to read key " + key, e);
        }
    }

    @Override
    public void put(String key, String value) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                upsert(conn, key, value);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw new RuntimeException("failed to write key " + key, e);
            }
        } catch (SQLException e) {
            throw new RuntimeException("failed to write key " + key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM " + table + " WHERE k = ?")) {
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
        try (Connection conn = dataSource.getConnection();
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
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    String current = selectForUpdate(conn, key);
                    String next = mutator.apply(current);
                    // mirror FileStore/InMemoryStore: a null mutator result still creates/keeps
                    // the key, written as an empty string, rather than deleting it.
                    upsert(conn, key, next != null ? next : "");
                    conn.commit();
                    return;
                } catch (SQLException e) {
                    conn.rollback();
                    lastError = e;
                    // most likely a primary-key collision from a concurrent update() that also
                    // just inserted this same previously-absent key (FOR UPDATE locks an
                    // existing row, not one that doesn't exist yet) - retry the whole cycle.
                }
            } catch (SQLException e) {
                lastError = e;
            }
        }
        throw new RuntimeException(
                "failed to update key " + key + " after " + MAX_ATTEMPTS + " attempts", lastError);
    }

    @Override
    public List<String> listKeys(String prefix) {
        List<String> keys = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT k FROM " + table + " WHERE k LIKE ? ESCAPE '\\'")) {
            ps.setString(1, escapeLikePattern(prefix) + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) keys.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new RuntimeException("failed to list keys with prefix " + prefix, e);
        }
        return keys;
    }

    // Portable UPDATE-then-INSERT upsert (see class javadoc): avoids H2 MERGE / MySQL
    // ON DUPLICATE KEY UPDATE / Postgres ON CONFLICT dialect differences. Runs inside the
    // caller's transaction so the two statements commit/rollback together.
    private void upsert(Connection conn, String key, String value) throws SQLException {
        try (PreparedStatement update = conn.prepareStatement("UPDATE " + table + " SET v = ? WHERE k = ?")) {
            update.setString(1, value);
            update.setString(2, key);
            if (update.executeUpdate() > 0) return;
        }
        try (PreparedStatement insert = conn.prepareStatement("INSERT INTO " + table + " (k, v) VALUES (?, ?)")) {
            insert.setString(1, key);
            insert.setString(2, value);
            insert.executeUpdate();
        }
    }

    private String selectForUpdate(Connection conn, String key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT v FROM " + table + " WHERE k = ? FOR UPDATE")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private void createTableIfAbsent() {
        try (Connection conn = dataSource.getConnection()) {
            String valueType = valueColumnType(conn);
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS " + table
                        + " (k VARCHAR(512) PRIMARY KEY, v " + valueType + ")");
            }
        } catch (SQLException e) {
            throw new RuntimeException("failed to create store table " + table, e);
        }
    }

    // See class javadoc: CLOB isn't native on MySQL/PostgreSQL, so pick the equivalent
    // large-text type per database product rather than hardcoding one dialect.
    private static String valueColumnType(Connection conn) throws SQLException {
        String product = conn.getMetaData().getDatabaseProductName();
        if (product == null) return "CLOB";
        String p = product.toLowerCase(Locale.ROOT);
        if (p.contains("mysql") || p.contains("mariadb")) return "LONGTEXT";
        if (p.contains("postgresql")) return "TEXT";
        return "CLOB"; // H2 and other CLOB-native databases
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
