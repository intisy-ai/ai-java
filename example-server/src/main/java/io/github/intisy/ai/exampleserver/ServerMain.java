package io.github.intisy.ai.exampleserver;

import io.github.intisy.ai.jvm.AiJava;
import io.github.intisy.ai.jvm.Storage;
import io.github.intisy.ai.jvm.backend.Backend;
import io.github.intisy.ai.shared.routing.RoutingProfile;
import io.github.intisy.ai.shared.spi.Store;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Boots the example server. Demonstrates "completely customizable backend": the store is chosen
 * from one place ({@code -Dexampleserver.store=memory|file}) and composed into a {@link Backend}
 * the whole server runs on. Provider jars are discovered from {@code -Dexampleserver.providersDir}
 * (set by the Gradle {@code run} task); the port from {@code -Dexampleserver.port} (default 8787).
 */
public final class ServerMain {

    private static final String CONFIG_FILE = "example-server-routing.json";

    private ServerMain() {
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getProperty("exampleserver.port", "8787"));
        Store store = chooseStore();
        Backend backend = Backend.builder().store(store).build();

        try (AiJava ai = AiJava.builder()
                .backend(backend)
                .providersDir(providersDir())
                .build()) {

            ServerSeeds.seedEcho(ai.store(), ai.jsonCodec(), CONFIG_FILE);
            RoutingProfile profile = ServerProfile.echoTiers(CONFIG_FILE);
            ExampleServer server = ExampleServer.start(ai, profile, port);

            System.out.println("example-server listening on http://127.0.0.1:" + server.port());
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
