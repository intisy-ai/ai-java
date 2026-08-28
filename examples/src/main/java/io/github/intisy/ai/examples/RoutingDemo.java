package io.github.intisy.ai.examples;

import io.github.intisy.ai.examples.support.DemoProfiles;
import io.github.intisy.ai.examples.support.DemoSeeds;
import io.github.intisy.ai.examples.support.Requests;
import io.github.intisy.ai.examples.support.Section;
import io.github.intisy.ai.jvm.AiJava;
import io.github.intisy.ai.jvm.Storage;
import io.github.intisy.ai.shared.routing.RoutingProfile;
import io.github.intisy.ai.api.seam.Store;
import io.github.intisy.ai.api.seam.HttpResponse;

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
        /** The response a request served by its first-choice provider produced. */
        public final HttpResponse normal;
        /** The response produced once the first choice answered a rate limit. */
        public final HttpResponse fallback;
        /** The response produced once every provider in the chain was exhausted. */
        public final HttpResponse exhaustion;
        /** The model listing the router answered. */
        public final HttpResponse models;

        /**
         * @param normal the first-choice response
         * @param fallback the response after a rate limit moved the request on
         * @param exhaustion the response once the whole chain was exhausted
         * @param models the model listing
         */
        public Result(HttpResponse normal, HttpResponse fallback, HttpResponse exhaustion, HttpResponse models) {
            this.normal = normal;
            this.fallback = fallback;
            this.exhaustion = exhaustion;
            this.models = models;
        }
    }

    /**
     * Runs the walk and prints every outcome.
     *
     * @param providersDir the directory the provider jars are staged in
     * @throws IOException when the staged jars or the temp store cannot be read
     */
    public static void run(Path providersDir) throws IOException {
        Result result = execute(providersDir);

        Section.header("RoutingDemo - tier fallback, model rewrite, exhaustion, /v1/models");
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
    /**
     * Runs the walk without printing, so a test can assert on each response.
     *
     * @param providersDir the directory the provider jars are staged in
     * @return the four responses the walk produced
     * @throws IOException when the staged jars or the temp store cannot be read
     */
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
