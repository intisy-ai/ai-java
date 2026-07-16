package io.github.intisy.ai.exampleserver;

import io.github.intisy.ai.exampleserver.admin.AccountAdmin;
import io.github.intisy.ai.exampleserver.admin.RoutingAdmin;
import io.github.intisy.ai.exampleserver.api.ManagementApi;
import io.github.intisy.ai.exampleserver.discovery.GithubOrgProviderSource;
import io.github.intisy.ai.exampleserver.discovery.ProviderDiscovery;
import io.github.intisy.ai.exampleserver.discovery.ProviderRegistryHolder;
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
 * from one place ({@code -Dexampleserver.store=memory|file}) and composed into a {@link Backend}
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

        try (AiJava ai = AiJava.builder()
                .backend(backend)
                .build()) {

            RoutingProfile profile = ServerProfile.echoTiers(CONFIG_FILE);

            AiJava.WiredRouter router = ai.router(profile,
                    id -> holder.asHandlerResolver().resolve(id), holder::listProviderIds);

            AccountAdmin admin = new AccountAdmin(new AccountStore(ai.store(), ai.jsonCodec()), ai.clock());
            RoutingAdmin routing = new RoutingAdmin(ai.store(), ai.jsonCodec(), profile, holder, ai.logger());
            ManagementApi api = new ManagementApi(holder::listProviderIds, admin, ai.jsonCodec(),
                    new GithubOrgProviderSource(ai.jsonCodec()), providersDir, holder, routing);
            ExampleServer server = ExampleServer.start(router, port, api);

            System.out.println("example-server listening on http://127.0.0.1:" + server.port());
            System.out.println("  GET  /              dashboard (providers + accounts)");
            System.out.println("  GET  /api/providers management API");
            System.out.println("  GET  /api/providers/available   list installable providers");
            System.out.println("  POST /api/providers/install      {\"name\":\"<entry name>\"}");
            System.out.println("  POST /api/providers/{id}/models/discover");
            System.out.println("  GET  /api/routing/catalog");
            System.out.println("  GET  /api/routing/model-map");
            System.out.println("  PUT  /api/routing/model-map      {\"map\":{...}}");
            System.out.println("  POST /v1/messages  {\"model\":\"claude-haiku-4\",\"messages\":[]}");
            System.out.println("  GET  /v1/models");
            System.out.println("  GET  /healthz");

            Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
            Thread.currentThread().join(); // block forever until the process is killed
        }
    }

    private static Store chooseStore() {
        String kind = System.getProperty("exampleserver.store", "memory");
        if ("file".equals(kind)) {
            return Storage.file(Paths.get(System.getProperty("exampleserver.configDir", "config")));
        }
        return Storage.memory();
    }

    private static Path providersDir() {
        String dir = System.getProperty("exampleserver.providersDir", "providers");
        return Paths.get(dir);
    }
}
