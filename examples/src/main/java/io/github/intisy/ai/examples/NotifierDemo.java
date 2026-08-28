package io.github.intisy.ai.examples;

import io.github.intisy.ai.examples.support.DemoProfiles;
import io.github.intisy.ai.examples.support.DemoSeeds;
import io.github.intisy.ai.examples.support.InProcessProviders;
import io.github.intisy.ai.examples.support.Requests;
import io.github.intisy.ai.examples.support.Section;
import io.github.intisy.ai.examples.support.Workspace;
import io.github.intisy.ai.jvm.AiJava;
import io.github.intisy.ai.jvm.backend.notify.JsonlNotifier;
import io.github.intisy.ai.jvm.Storage;
import io.github.intisy.ai.shared.routing.RoutingProfile;
import io.github.intisy.ai.api.seam.Store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shows the default notification path for a file-backed store: with no notifier injected, a
 * file-backed AiJava uses the real {@link JsonlNotifier}, which appends one JSON line per notice to
 * {@code <config>/../cache/auth-notifications.jsonl}, the queue a host app's hook drains into a
 * user-visible toast. Triggering a routing fallback produces a notice; this demo prints the line the
 * notifier wrote.
 */
public final class NotifierDemo {

    private static final String CONFIG_FILE = "examples-notifier.json";
    /** The path (relative to the store's config folder) the JsonlNotifier appends to. */
    /** Where the store-derived notifier writes, relative to the config directory. */
    public static final String NOTIFICATIONS_RELATIVE_PATH = "../cache/auth-notifications.jsonl";

    private NotifierDemo() {
    }

    /** What the default notifier produced, captured before the temp store is deleted. */
    public static final class Result {
        /** The notifier class the host resolved from the chosen storage. */
        public final String notifierType;
        /** Everything that notifier wrote. */
        public final String jsonlContent;

        /**
         * @param notifierType the notifier class the host resolved
         * @param jsonlContent everything it wrote
         */
        public Result(String notifierType, String jsonlContent) {
            this.notifierType = notifierType;
            this.jsonlContent = jsonlContent;
        }
    }

    /**
     * Runs the walk and prints every outcome.
     *
     * @throws IOException when the temp store cannot be worked with
     */
    public static void run() throws IOException {
        Result result = execute();

        Section.header("NotifierDemo - default JsonlNotifier for a file-backed store");
        Section.detail("file-backed AiJava's default notifier is: " + result.notifierType);
        Section.detail("a routing fallback wrote this notification line:");
        Section.detail("  " + result.jsonlContent.trim());
    }

    /**
     * Runs the walk without printing, so a test can assert on what was written.
     *
     * @return the resolved notifier and what it wrote
     * @throws IOException when the temp store cannot be worked with
     */
    public static Result execute() throws IOException {
        try (Workspace workspace = Workspace.create("examples-notifier-")) {
            Path configFolder = workspace.resolve("config");
            AiJava app = AiJava.builder().storage(Storage.file(configFolder)).build();
            String notifierType = app.notifier().getClass().getSimpleName();

            Store store = app.store();
            DemoSeeds.seedInProcessFallback(store, app.jsonCodec(), CONFIG_FILE);

            // rl (429) -> ok forces a fallback, and the router announces it via the default notifier.
            RoutingProfile profile = DemoProfiles.multiTier(CONFIG_FILE, "ok");
            AiJava.WiredRouter router = app.router(profile, InProcessProviders.resolver(), InProcessProviders::ids);
            router.route(Requests.messages("claude-opus-4-1"));

            Path notifications = configFolder.resolve(NOTIFICATIONS_RELATIVE_PATH).normalize();
            String content = Files.exists(notifications)
                    ? new String(Files.readAllBytes(notifications), StandardCharsets.UTF_8)
                    : "";
            return new Result(notifierType, content);
        }
    }
}
