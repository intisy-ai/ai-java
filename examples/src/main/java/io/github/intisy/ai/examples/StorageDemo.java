package io.github.intisy.ai.examples;

import io.github.intisy.ai.examples.support.DemoProfiles;
import io.github.intisy.ai.examples.support.DemoSeeds;
import io.github.intisy.ai.examples.support.H2Support;
import io.github.intisy.ai.examples.support.InProcessProviders;
import io.github.intisy.ai.examples.support.Requests;
import io.github.intisy.ai.examples.support.Section;
import io.github.intisy.ai.examples.support.Workspace;
import io.github.intisy.ai.jvm.AiJava;
import io.github.intisy.ai.jvm.Storage;
import io.github.intisy.ai.shared.routing.RoutingProfile;
import io.github.intisy.ai.shared.spi.Store;
import io.github.intisy.ai.shared.spi.http.HttpResponse;

import java.io.IOException;

/**
 * Shows that storage is an EXPLICIT, swappable choice. The exact same store-usage routine runs
 * unchanged against all three built-in backends ({@code Storage.file}, {@code Storage.memory},
 * {@code Storage.jdbc}) and produces identical results, and building without any storage fails
 * fast rather than silently defaulting to files.
 */
public final class StorageDemo {

    private static final String CONFIG_FILE = "examples-routing.json";

    private StorageDemo() {
    }

    /** The observable outcome of the shared routine, so all three backends can be compared for parity. */
    public static final class Result {
        public final String storedValue;
        public final int routedStatus;
        public final String routedBody;

        public Result(String storedValue, int routedStatus, String routedBody) {
            this.storedValue = storedValue;
            this.routedStatus = routedStatus;
            this.routedBody = routedBody;
        }
    }

    public static void run() throws IOException {
        Section.header("StorageDemo — storage is an explicit, swappable choice");

        Section.detail("build() with no storage is rejected (storage is never defaulted):");
        try {
            AiJava.builder().build();
        } catch (IllegalStateException expected) {
            Section.detail("  caught IllegalStateException: " + expected.getMessage());
        }

        try (Workspace workspace = Workspace.create("examples-storage-")) {
            Result file = roundTrip(Storage.file(workspace.resolve("config")));
            Section.detail("file   -> stored=" + file.storedValue + "  routed[" + file.routedStatus + "]=" + file.routedBody);

            Result memory = roundTrip(Storage.memory());
            Section.detail("memory -> stored=" + memory.storedValue + "  routed[" + memory.routedStatus + "]=" + memory.routedBody);

            Result jdbc = roundTrip(Storage.jdbc(H2Support.inMemoryDataSource()));
            Section.detail("jdbc   -> stored=" + jdbc.storedValue + "  routed[" + jdbc.routedStatus + "]=" + jdbc.routedBody);

            boolean identical = file.routedBody.equals(memory.routedBody)
                    && memory.routedBody.equals(jdbc.routedBody);
            Section.detail("all three backends produced identical routed responses: " + identical);
        }
    }

    /**
     * The backend-agnostic routine both the demo and the parity test run: a put/update/get round
     * trip plus a routed request. Nothing here mentions a specific backend, only the {@link Store}
     * passed in differs.
     */
    public static Result roundTrip(Store store) {
        AiJava app = AiJava.builder().storage(store).build();
        DemoSeeds.seedInProcessFallback(store, app.jsonCodec(), CONFIG_FILE);

        store.put("demo-note.json", "{\"count\":1}");
        store.update("demo-note.json", current -> "{\"count\":2}");
        String stored = store.get("demo-note.json");

        RoutingProfile profile = DemoProfiles.multiTier(CONFIG_FILE, "ok");
        AiJava.WiredRouter router = app.router(profile, InProcessProviders.resolver(), InProcessProviders::ids);
        HttpResponse response = router.route(Requests.messages("claude-opus-4-1"));

        return new Result(stored, response.status, response.body);
    }
}
