package io.github.intisy.ai.jvm;

import io.github.intisy.ai.shared.routing.HandlerCtx;
import io.github.intisy.ai.shared.routing.Provider;
import io.github.intisy.ai.shared.routing.RoutingProfile;
import io.github.intisy.ai.shared.spi.Store;
import io.github.intisy.ai.shared.spi.http.HttpRequest;
import io.github.intisy.ai.shared.spi.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 3: proves the {@code ServiceLoader}-based {@link ProviderRegistry} really is the seam
 * {@link AiJava#router(RoutingProfile)} wires in as its {@code HandlerResolver}, replacing the
 * hand-assembled test resolvers ({@code HandlerResolvers.fromRegistry(...)}) other tests
 * (e.g. {@link AiJavaTest}, {@link RouterJvmIntegrationTest}) build by hand.
 *
 * <p>{@link #writeStubProviderJar} packages the already-compiled {@link StubProvider} {@code
 * .class} (compiled as a normal part of this module's test-compile step — no {@code javac}
 * invoked at test runtime) plus a real {@code META-INF/services} registration into an actual
 * jar, then drops it into a temp "providers" directory — exactly the shape a real provider
 * module ships. {@link ProviderRegistry#fromDirectory} discovers it via {@code
 * ServiceLoader.load(Provider.class, classLoader)} over a dedicated {@code URLClassLoader}, and
 * {@code AiJava.builder().providersDir(...).build().router(profile)} routes a real request
 * through it end-to-end — proving the full chain: jar on disk -&gt; ServiceLoader discovery -&gt;
 * {@code HandlerResolvers.fromProviders} -&gt; {@code Router.route} dispatch. Dropping in a
 * SEPARATE real provider module (its own Gradle project, own jar, own release) is Task 4's job —
 * this only proves the registry wiring itself is correct.
 */
class ProviderRegistryTest {

    private static final String CONFIG_FILE = "provider-registry-test.json";

    @Test
    void fromDirectory_discoversJarProvider_andWiresAsAiJavaResolver(@TempDir Path tmp) throws IOException {
        Path providersDir = tmp.resolve("providers");
        Files.createDirectory(providersDir);
        writeStubProviderJar(providersDir.resolve("stub-provider.jar"));

        ProviderRegistry registry = ProviderRegistry.fromDirectory(providersDir);
        assertEquals(List.of("stub"), registry.listProviderIds());

        Store store = Storage.memory();
        seedModelMap(store);

        AiJava app = AiJava.builder().storage(store).providersDir(providersDir).build();
        assertEquals(List.of("stub"), app.providerRegistry().listProviderIds(),
                "AiJava.builder().providersDir(...) should discover the same jar-provided Provider");

        AiJava.WiredRouter router = app.router(profile());
        HttpResponse resp = router.route(post("/v1/messages", "{}"));

        assertEquals(200, resp.status);
        assertEquals("stub-ok:m-stub", resp.body,
                "the request should have been dispatched to the jar-loaded StubProvider, carrying the assigned model");
    }

    @Test
    void emptyOrMissingDirectory_yieldsNoProviders_notAnError(@TempDir Path tmp) {
        assertTrue(ProviderRegistry.fromDirectory(tmp.resolve("does-not-exist")).listProviderIds().isEmpty());
        assertTrue(ProviderRegistry.empty().listProviderIds().isEmpty());
    }

    @Test
    void noProvidersDirConfigured_aiJavaDefaultsToEmptyRegistry() {
        AiJava app = AiJava.builder().storage(Storage.memory()).build();
        assertTrue(app.providerRegistry().listProviderIds().isEmpty(),
                "providersDir is optional -- an unset AiJava should never guess a directory");
    }

    // -- fixtures -----------------------------------------------------------------

    private static RoutingProfile profile() {
        RoutingProfile p = new RoutingProfile();
        p.configFile = CONFIG_FILE;
        p.routingKey = "providerRouting";
        p.tierSourceProvider = "stub";
        p.tierOrder = Collections.singletonList("opus");
        p.tierFallback = Collections.singletonList("opus");
        p.tierRegex = Pattern.compile("^claude-([a-z]+)-\\d");
        p.envPrefix = "ANTHROPIC";
        p.defaultContext = 200000;
        p.defaultOutput = 64000;
        p.nativeRateLimit = info -> {
            RoutingProfile.Synth s = new RoutingProfile.Synth();
            s.status = 429;
            s.headers = new HashMap<>();
            s.body = "{}";
            return s;
        };
        return p;
    }

    private static void seedModelMap(Store store) {
        GsonJsonCodec json = new GsonJsonCodec();
        Map<String, Object> stub = new HashMap<>();
        stub.put("provider", "stub");
        stub.put("model", "m-stub");
        Map<String, Object> doc = new HashMap<>();
        doc.put("modelMap", Collections.singletonMap("opus", List.of(stub)));
        store.put(CONFIG_FILE, json.stringify(doc));
    }

    private static HttpRequest post(String url, String body) {
        HttpRequest req = new HttpRequest();
        req.method = "POST";
        req.url = url;
        req.headers = new HashMap<>();
        req.body = body;
        return req;
    }

    private static void writeStubProviderJar(Path jarPath) throws IOException {
        String className = StubProvider.class.getName();
        String classResourcePath = className.replace('.', '/') + ".class";
        byte[] classBytes = readClassBytes(classResourcePath);

        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(jarPath))) {
            jar.putNextEntry(new JarEntry(classResourcePath));
            jar.write(classBytes);
            jar.closeEntry();

            jar.putNextEntry(new JarEntry("META-INF/services/" + Provider.class.getName()));
            jar.write(className.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }

    private static byte[] readClassBytes(String classResourcePath) throws IOException {
        try (InputStream in = ProviderRegistryTest.class.getClassLoader().getResourceAsStream(classResourcePath)) {
            if (in == null) throw new IllegalStateException("missing compiled class on test classpath: " + classResourcePath);
            return in.readAllBytes();
        }
    }

    /** Minimal {@link Provider} used only to prove {@link ProviderRegistry} discovery + wiring. */
    public static final class StubProvider implements Provider {
        @Override
        public String id() {
            return "stub";
        }

        @Override
        public HttpResponse handle(HttpRequest req, HandlerCtx ctx) {
            HttpResponse resp = new HttpResponse();
            resp.status = 200;
            resp.headers = new HashMap<>();
            resp.body = "stub-ok:" + ctx.model;
            return resp;
        }
    }
}
