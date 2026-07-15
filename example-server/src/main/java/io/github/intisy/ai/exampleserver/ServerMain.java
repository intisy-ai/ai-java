package io.github.intisy.ai.exampleserver;

import io.github.intisy.ai.exampleserver.admin.AccountAdmin;
import io.github.intisy.ai.exampleserver.api.ManagementApi;
import io.github.intisy.ai.exampleserver.discovery.ProviderDiscovery;
import io.github.intisy.ai.jvm.AiJava;
import io.github.intisy.ai.jvm.Storage;
import io.github.intisy.ai.jvm.backend.Backend;
import io.github.intisy.ai.jvm.provider.ProviderRegistry;
import io.github.intisy.ai.shared.model.Account;
import io.github.intisy.ai.shared.routing.RoutingProfile;
import io.github.intisy.ai.shared.spi.Store;
import io.github.intisy.ai.shared.store.AccountStore;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Boots the example server. Demonstrates "completely customizable backend": the store is chosen
 * from one place ({@code -Dexampleserver.store=memory|file}) and composed into a {@link Backend}
 * the whole server runs on. Provider jars are discovered via {@link ProviderDiscovery} from
 * {@code -Dexampleserver.providersDir} (set by the Gradle {@code run} task), optionally fetching
 * jars from the intisy-ai GitHub org first when {@code -Dexampleserver.fetchProviders=true} (default
 * {@code false}, so a plain run stays offline-clean). The port comes from
 * {@code -Dexampleserver.port} (default 8787). On top of routing, this wires the {@code /api}
 * management endpoints and the {@code /} dashboard, seeding a couple of demo accounts so both have
 * something to show out of the box.
 */
public final class ServerMain {

    private static final String CONFIG_FILE = "example-server-routing.json";

    private ServerMain() {
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getProperty("exampleserver.port", "8787"));
        Store store = chooseStore();
        Backend backend = Backend.builder().store(store).build();

        boolean fetchFromOrg = Boolean.getBoolean("exampleserver.fetchProviders");
        ProviderRegistry registry = ProviderDiscovery.resolve(providersDir(), fetchFromOrg);

        try (AiJava ai = AiJava.builder()
                .backend(backend)
                .providerRegistry(registry)
                .build()) {

            ServerSeeds.seedEcho(ai.store(), ai.jsonCodec(), CONFIG_FILE);
            seedDemoAccounts(ai);
            RoutingProfile profile = ServerProfile.echoTiers(CONFIG_FILE);

            AccountAdmin admin = new AccountAdmin(new AccountStore(ai.store(), ai.jsonCodec()), ai.clock());
            ManagementApi api = new ManagementApi(registry::listProviderIds, admin, ai.jsonCodec());
            ExampleServer server = ExampleServer.start(ai, profile, port, api);

            System.out.println("example-server listening on http://127.0.0.1:" + server.port());
            System.out.println("  GET  /              dashboard (providers + accounts)");
            System.out.println("  GET  /api/providers management API");
            System.out.println("  POST /v1/messages  {\"model\":\"claude-haiku-4\",\"messages\":[]}");
            System.out.println("  GET  /v1/models");
            System.out.println("  GET  /healthz");
            System.out.println("  (set -Dexampleserver.fetchProviders=true to pull provider jars from intisy-ai)");

            Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
            Thread.currentThread().join(); // block forever until the process is killed
        }
    }

    /** Seeds 1-2 demo accounts for the echo provider so the dashboard's account panel has content. */
    private static void seedDemoAccounts(AiJava ai) {
        AccountStore accounts = new AccountStore(ai.store(), ai.jsonCodec());
        accounts.add("echo", demoAccount("demo-1@example"));
        accounts.add("echo", demoAccount("demo-2@example"));
    }

    private static Account demoAccount(String id) {
        Account account = new Account();
        account.id = id;
        account.email = id;
        account.enabled = true;
        return account;
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
