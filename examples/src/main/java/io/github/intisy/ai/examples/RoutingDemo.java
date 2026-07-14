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

/**
 * Shows the routing engine's four consumer-visible behaviors, all through the two jar-loaded example
 * providers and a realistic multi-tier {@code RoutingProfile}:
 * <ol>
 *   <li>a normal routed request, with the requested tier model rewritten to the provider's backend model;</li>
 *   <li>fallback: the primary provider is rate-limited (429), so routing advances to the healthy one;</li>
 *   <li>exhaustion: a tier whose only provider is rate-limited yields a synthesized native 429;</li>
 *   <li>the {@code /v1/models} catalog, assembled from the discovered providers' cached models.</li>
 * </ol>
 * The store's model map wires each tier: {@code haiku=[echo]}, {@code opus=[ratelimited, echo]},
 * {@code sonnet=[ratelimited]} (see {@link DemoSeeds#seedJarRouting}).
 */
public final class RoutingDemo {

    private static final String CONFIG_FILE = "examples-routing-demo.json";

    private RoutingDemo() {
    }

    /** The four routed responses, so a test can assert each behavior without parsing stdout. */
    public static final class Result {
        public final HttpResponse normal;
        public final HttpResponse fallback;
        public final HttpResponse exhaustion;
        public final HttpResponse models;

        public Result(HttpResponse normal, HttpResponse fallback, HttpResponse exhaustion, HttpResponse models) {
            this.normal = normal;
            this.fallback = fallback;
            this.exhaustion = exhaustion;
            this.models = models;
        }
    }

    public static void run(Path providersDir) throws IOException {
        Result result = execute(providersDir);

        Section.header("RoutingDemo — tier fallback, model rewrite, exhaustion, /v1/models");
        Section.detail("(a) normal request  claude-haiku-4  -> requested tier model rewritten to the backend model:");
        Section.detail("    status=" + result.normal.status + " body=" + result.normal.body);
        Section.detail("(b) fallback        claude-opus-4-1 -> primary 'ratelimited' 429s, routing falls back to 'echo':");
        Section.detail("    status=" + result.fallback.status + " body=" + result.fallback.body);
        Section.detail("(c) exhaustion      claude-sonnet-4 -> only provider is rate-limited, native 429 synthesized:");
        Section.detail("    status=" + result.exhaustion.status + " body=" + result.exhaustion.body);
        Section.detail("(d) GET /v1/models  -> catalog assembled from the discovered providers:");
        Section.detail("    status=" + result.models.status + " body=" + result.models.body);
    }

    /** Routes the four scenarios through jar-loaded providers and returns their responses. */
    public static Result execute(Path providersDir) throws IOException {
        try (AiJava app = AiJava.builder().storage(Storage.memory()).providersDir(providersDir).build()) {
            Store store = app.store();
            DemoSeeds.seedJarRouting(store, app.jsonCodec(), CONFIG_FILE);

            RoutingProfile profile = DemoProfiles.multiTier(CONFIG_FILE, "echo");
            AiJava.WiredRouter router = app.router(profile);

            HttpResponse normal = router.route(Requests.messages("claude-haiku-4"));
            HttpResponse fallback = router.route(Requests.messages("claude-opus-4-1"));
            HttpResponse exhaustion = router.route(Requests.messages("claude-sonnet-4"));
            HttpResponse models = router.route(Requests.get("/v1/models"));
            return new Result(normal, fallback, exhaustion, models);
        }
    }
}
