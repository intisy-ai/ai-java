package io.github.intisy.ai.examples;

import io.github.intisy.ai.examples.support.DemoProfiles;
import io.github.intisy.ai.examples.support.DemoSeeds;
import io.github.intisy.ai.examples.support.Requests;
import io.github.intisy.ai.examples.support.Section;
import io.github.intisy.ai.jvm.AiJava;
import io.github.intisy.ai.jvm.Storage;
import io.github.intisy.ai.shared.routing.RoutingProfile;
import io.github.intisy.ai.shared.spi.Store;
import io.github.intisy.ai.shared.spi.http.HttpResponse;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Shows the provider jar discovery seam: point {@code AiJava.builder().providersDir(dir)} at a
 * directory of provider jars and the discovered providers back {@code router(profile)} with ZERO
 * registry code — no imports of the provider classes, no manual registration. The provider jar
 * ({@code :examples-provider}) is staged into that directory by Gradle before this runs. Also shows
 * the {@code close()} lifecycle: AiJava owns the jar {@code URLClassLoader}, so it is used in a
 * try-with-resources block and a fresh instance re-discovers cleanly afterward.
 */
public final class ProviderRegistryDemo {

    private static final String CONFIG_FILE = "examples-registry.json";

    private ProviderRegistryDemo() {
    }

    public static void run(Path providersDir) throws IOException {
        Section.header("ProviderRegistryDemo — discover provider jars via ServiceLoader");
        Section.detail("scanning providers directory: " + providersDir);

        // try-with-resources: AiJava.close() releases the jar URLClassLoader the registry keeps open.
        try (AiJava app = AiJava.builder().storage(Storage.memory()).providersDir(providersDir).build()) {
            List<String> ids = app.providerRegistry().listProviderIds();
            Section.detail("discovered provider ids (from one jar, via META-INF/services): " + ids);

            Store store = app.store();
            DemoSeeds.seedJarRouting(store, app.jsonCodec(), CONFIG_FILE);

            RoutingProfile profile = DemoProfiles.multiTier(CONFIG_FILE, "echo");
            AiJava.WiredRouter router = app.router(profile);
            HttpResponse response = router.route(Requests.messages("claude-haiku-4"));
            Section.detail("routed a request straight through the jar-loaded echo provider:");
            Section.detail("  status=" + response.status + " body=" + response.body);
        }
        Section.detail("AiJava closed — jar classloader released.");

        // A brand-new instance re-discovers the same jars: proves close() didn't leave anything stuck.
        try (AiJava fresh = AiJava.builder().storage(Storage.memory()).providersDir(providersDir).build()) {
            Section.detail("fresh instance re-discovered: " + fresh.providerRegistry().listProviderIds());
        }
    }

    /** Discovers the provider ids from a fresh AiJava over the given directory (used by tests). */
    public static List<String> discover(Path providersDir) throws IOException {
        try (AiJava app = AiJava.builder().storage(Storage.memory()).providersDir(providersDir).build()) {
            return app.providerRegistry().listProviderIds();
        }
    }
}
