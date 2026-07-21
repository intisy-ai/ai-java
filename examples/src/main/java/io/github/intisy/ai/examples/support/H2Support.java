package io.github.intisy.ai.examples.support;

import org.h2.jdbcx.JdbcDataSource;

import javax.sql.DataSource;
import java.util.UUID;

/**
 * Builds a fresh in-memory H2 {@link DataSource}, the kind of {@code javax.sql.DataSource} a server
 * would normally get from its connection pool and hand to {@code Storage.jdbc(...)}. Each call uses a
 * unique database name so backends never share state, and {@code DB_CLOSE_DELAY=-1} keeps the
 * in-memory database alive for the process's lifetime rather than vanishing when the first
 * connection closes.
 */
public final class H2Support {

    private H2Support() {
    }

    public static DataSource inMemoryDataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:examples-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }
}
