package io.github.intisy.ai.exampleserver;

import io.github.intisy.ai.exampleserver.admin.AccountAdmin;
import io.github.intisy.ai.exampleserver.admin.ConfigAdmin;
import io.github.intisy.ai.exampleserver.admin.OAuthAdmin;
import io.github.intisy.ai.exampleserver.admin.ProxyAdmin;
import io.github.intisy.ai.exampleserver.admin.QuotaAdmin;
import io.github.intisy.ai.exampleserver.admin.RoutingAdmin;
import io.github.intisy.ai.exampleserver.api.ManagementApi;
import io.github.intisy.ai.exampleserver.discovery.GithubOrgProviderSource;
import io.github.intisy.ai.exampleserver.discovery.GithubOrgProxySource;
import io.github.intisy.ai.exampleserver.discovery.GithubOrgScan;
import io.github.intisy.ai.exampleserver.discovery.ProviderDiscovery;
import io.github.intisy.ai.exampleserver.discovery.ProviderRegistryHolder;
import io.github.intisy.ai.exampleserver.discovery.ProxyDiscovery;
import io.github.intisy.ai.exampleserver.discovery.ProxyRegistryHolder;
import io.github.intisy.ai.jvm.AiJava;
import io.github.intisy.ai.jvm.Storage;
import io.github.intisy.ai.jvm.backend.Backend;
import io.github.intisy.ai.jvm.provider.ProviderRegistry;
import io.github.intisy.ai.shared.routing.RoutingProfile;
import io.github.intisy.ai.shared.spi.Store;
import io.github.intisy.ai.shared.store.AccountStore;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Boots the example server. Demonstrates "completely customizable backend": the store is chosen
 * from one place ({@code -Dexampleserver.store=sqlite|memory|file|jdbc}) and composed into a {@link Backend}
 * the whole server runs on. Provider jars are discovered via {@link ProviderDiscovery} from
 * {@code -Dexampleserver.providersDir} (set by the Gradle {@code run} task) — startup only ever
 * reads whatever's already on disk, never the network. That registry is held in a
 * {@link ProviderRegistryHolder} so it can be refreshed after a provider is installed on demand
 * (a later task's job) without restarting the process: the router is wired with lambdas that
 * read through the holder, so every request sees the current registry. The port comes from
 * {@code -Dexampleserver.port} (default 8787). On top of routing, this wires the {@code /api}
 * management endpoints and the {@code /} dashboard. The running server starts with NO fake/demo
 * providers or accounts seeded — {@code -Dexampleserver.providersDir} points at an empty directory
 * by default (see {@code build.gradle}'s {@code run} task), and every real provider/account comes
 * only from an on-demand install via the API/dashboard (backed by {@link GithubOrgProviderSource}).
 * Tests that need the echo fixture seed it themselves via {@code ServerSeeds.seedEcho}.
 */
public final class ServerMain {

    private static final String CONFIG_FILE = "example-server-routing.json";

    private ServerMain() {
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getProperty("exampleserver.port", "8787"));
        Store store = chooseStore();
        Backend backend = Backend.builder().store(store).build();

        Path providersDir = providersDir();
        ProviderRegistry registry = ProviderDiscovery.resolve(providersDir);
        ProviderRegistryHolder holder = new ProviderRegistryHolder(registry);

        Path proxiesDir = proxiesDir();
        ProxyRegistryHolder proxyHolder = new ProxyRegistryHolder(ProxyDiscovery.resolve(proxiesDir));

        try (AiJava ai = AiJava.builder()
                .backend(backend)
                .build()) {

            RoutingProfile profile = ServerProfile.echoTiers(CONFIG_FILE);

            AiJava.WiredRouter router = ai.router(profile,
                    id -> holder.asHandlerResolver().resolve(id), holder::listProviderIds);

            AccountAdmin admin = new AccountAdmin(new AccountStore(ai.store(), ai.jsonCodec()), ai.clock());
            RoutingAdmin routing = new RoutingAdmin(ai.store(), ai.jsonCodec(), profile, holder, ai.logger());
            QuotaAdmin quota = new QuotaAdmin(ai.store(), ai.jsonCodec(), holder, ai.logger());
            ConfigAdmin config = new ConfigAdmin(ai.store(), ai.jsonCodec(), holder, ai.logger());
            OAuthAdmin oauth = new OAuthAdmin(ai.store(), ai.jsonCodec(), holder, ai.logger(), admin);
            ProxyManager proxyManager = new ProxyManager(ai, holder, proxyHolder, ai.store(), ai.jsonCodec(), ai.logger());
            ProxyAdmin proxyAdmin = new ProxyAdmin(proxyManager);
            // ONE shared org scan feeds both sources so the org is only ever scanned once.
            GithubOrgScan orgScan = new GithubOrgScan(ai.jsonCodec());
            GithubOrgProviderSource providerSource = new GithubOrgProviderSource(orgScan);
            GithubOrgProxySource proxySource = new GithubOrgProxySource(orgScan);
            ManagementApi api = new ManagementApi(holder::listProviderIds, admin, ai.jsonCodec(),
                    providerSource, providersDir, holder, routing, quota, config, oauth,
                    proxyAdmin, proxySource, proxyHolder, proxiesDir);
            ExampleServer server = ExampleServer.start(router, port, api);
            proxyManager.startEnabledOnBoot();

            System.out.println("example-server listening on http://127.0.0.1:" + server.port());
            System.out.println("  GET  /              dashboard (providers + accounts)");
            System.out.println("  GET  /api/providers management API");
            System.out.println("  GET  /api/providers/available   list installable providers");
            System.out.println("  POST /api/providers/install      {\"name\":\"<entry name>\"}");
            System.out.println("  POST /api/providers/{id}/models/discover");
            System.out.println("  POST /api/providers/{id}/quota/refresh");
            System.out.println("  GET  /api/routing/catalog");
            System.out.println("  GET  /api/routing/model-map");
            System.out.println("  PUT  /api/routing/model-map      {\"map\":{...}}");
            System.out.println("  GET  /api/providers/{id}/config");
            System.out.println("  PUT  /api/providers/{id}/config  {\"values\":{...}}");
            System.out.println("  POST /api/providers/{id}/oauth/authorize");
            System.out.println("  POST /api/providers/{id}/oauth/complete   {\"code\":..,\"state\":..}");
            System.out.println("  GET  /api/proxies");
            System.out.println("  GET  /api/proxies/available     list installable proxies");
            System.out.println("  POST /api/proxies/install         {\"name\":\"<entry name>\"}");
            System.out.println("  DELETE /api/proxies/{id}");
            System.out.println("  PUT  /api/proxies/{id}          {\"port\":N}");
            System.out.println("  POST /api/proxies/{id}/start | /stop");
            System.out.println("  POST /v1/messages  {\"model\":\"claude-haiku-4\",\"messages\":[]}");
            System.out.println("  GET  /v1/models");
            System.out.println("  GET  /healthz");

            Runtime.getRuntime().addShutdownHook(new Thread(() -> { proxyManager.stopAll(); server.stop(); }));
            Thread.currentThread().join(); // block forever until the process is killed
        }
    }

    /** Package-private + testable: maps a store kind + location to a concrete {@link Store}. */
    static Store storeFor(String kind, String location) {
        switch (kind) {
            case "memory":
                return Storage.memory();
            case "file":
                return Storage.file(Paths.get(location));
            case "sqlite": {
                org.sqlite.SQLiteDataSource ds = new org.sqlite.SQLiteDataSource();
                ds.setUrl("jdbc:sqlite:" + location);
                return Storage.jdbc(ds);
            }
            case "jdbc": {
                if (location == null || location.trim().isEmpty()) {
                    throw new IllegalArgumentException("exampleserver.store=jdbc requires -Dexampleserver.jdbcUrl");
                }
                String user = System.getProperty("exampleserver.jdbcUser");
                String password = System.getProperty("exampleserver.jdbcPassword");
                return Storage.jdbc(new DriverManagerDataSource(location, user, password));
            }
            default:
                // Unrecognized kind used to silently fall back to Storage.memory() (ephemeral),
                // which would quietly discard every write for an operator who asked for a
                // persistent store (or made a typo) - fail loud instead.
                throw new IllegalArgumentException(
                        "unknown exampleserver.store: " + kind + " (expected sqlite|memory|file|jdbc)");
        }
    }

    private static Store chooseStore() {
        // DEFAULT is now "sqlite" (persistent) so caching (accounts/quota/models/proxies/routing)
        // survives restarts out of the box; memory/file/jdbc remain selectable.
        String kind = System.getProperty("exampleserver.store", "sqlite");
        String location;
        if ("sqlite".equals(kind)) {
            location = System.getProperty("exampleserver.dbPath", "ai-java.db");
        } else if ("jdbc".equals(kind)) {
            location = System.getProperty("exampleserver.jdbcUrl");
        } else {
            location = System.getProperty("exampleserver.configDir", "config");
        }
        return storeFor(kind, location);
    }

    /**
     * Minimal {@link javax.sql.DataSource} wrapping {@link java.sql.DriverManager} for a
     * caller-supplied generic JDBC URL (e.g. {@code -Dexampleserver.store=jdbc}). Only
     * {@link #getConnection()} is exercised by {@link io.github.intisy.ai.jvm.backend.store.JdbcStore};
     * the rest of the interface is implemented minimally to satisfy the contract.
     */
    private static final class DriverManagerDataSource implements javax.sql.DataSource {
        private final String url;
        private final String user;
        private final String password;

        DriverManagerDataSource(String url, String user, String password) {
            this.url = url;
            this.user = user;
            this.password = password;
        }

        @Override
        public java.sql.Connection getConnection() throws java.sql.SQLException {
            return (user == null || user.isEmpty())
                    ? java.sql.DriverManager.getConnection(url)
                    : java.sql.DriverManager.getConnection(url, user, password);
        }

        @Override
        public java.sql.Connection getConnection(String username, String pass) throws java.sql.SQLException {
            return java.sql.DriverManager.getConnection(url, username, pass);
        }

        @Override
        public java.io.PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(java.io.PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public java.util.logging.Logger getParentLogger() throws java.sql.SQLFeatureNotSupportedException {
            throw new java.sql.SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws java.sql.SQLException {
            throw new java.sql.SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }

    private static Path providersDir() {
        String dir = System.getProperty("exampleserver.providersDir", "providers");
        return Paths.get(dir);
    }

    private static Path proxiesDir() {
        String dir = System.getProperty("exampleserver.proxiesDir", "proxies");
        return Paths.get(dir);
    }
}
