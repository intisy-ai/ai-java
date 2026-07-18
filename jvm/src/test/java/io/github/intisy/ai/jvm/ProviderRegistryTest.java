package io.github.intisy.ai.jvm;

import io.github.intisy.ai.jvm.backend.json.GsonJsonCodec;
import io.github.intisy.ai.jvm.provider.ProviderRegistry;
import io.github.intisy.ai.shared.routing.HandlerCtx;
import io.github.intisy.ai.shared.routing.Provider;
import io.github.intisy.ai.shared.routing.RoutingProfile;
import io.github.intisy.ai.shared.spi.Store;
import io.github.intisy.ai.shared.spi.http.HttpRequest;
import io.github.intisy.ai.shared.spi.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
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
 *
 * <p>{@code fromDirectory_discoversJarProvider_andWiresAsAiJavaResolver}'s {@link StubProvider}
 * is a nested class of THIS test, so it's already present on the test's own compile classpath —
 * the parent-first {@link ClassLoader} delegation the {@code URLClassLoader} in
 * {@link ProviderRegistry} uses would resolve it there without ever reading the jar this test
 * wrote, so that test alone can't tell a working {@code URLClassLoader} apart from a broken (or
 * even entirely absent) one. {@code fromDirectory_keepsClassLoaderOpen_...} closes that gap: it
 * compiles a {@code Provider} implementation (plus a helper class referenced only from inside
 * {@code handle(...)}, never during construction) with {@code javax.tools.ToolProvider}'s
 * compiler into a scratch directory that is NOT on this test's classpath, jars only that output,
 * and routes a real request through it — a regression of the {@code ProviderRegistry}
 * classloader-lifetime bug (closing the {@code URLClassLoader} right after {@code ServiceLoader}
 * discovery instead of keeping it open for the registry's lifetime) fails that test with a
 * {@link NoClassDefFoundError} instead of returning the expected response.
 */
class ProviderRegistryTest {

    private static final String CONFIG_FILE = "provider-registry-test.json";

    @Test
    void fromDirectory_discoversJarProvider_andWiresAsAiJavaResolver(@TempDir Path tmp) throws IOException {
        Path providersDir = tmp.resolve("providers");
        Files.createDirectory(providersDir);
        writeStubProviderJar(providersDir.resolve("stub-provider.jar"));

        // try-with-resources: the classloader backing these registries now stays open for the
        // registry's whole lifetime (the very fix under test), which on Windows keeps the jar's
        // file handle open until closed -- close() both before @TempDir tries to delete the jar.
        try (ProviderRegistry registry = ProviderRegistry.fromDirectory(providersDir)) {
            assertEquals(List.of("stub"), registry.listProviderIds());

            Store store = Storage.memory();
            seedModelMap(store);

            try (AiJava app = AiJava.builder().storage(store).providersDir(providersDir).build()) {
                assertEquals(List.of("stub"), app.providerRegistry().listProviderIds(),
                        "AiJava.builder().providersDir(...) should discover the same jar-provided Provider");

                AiJava.WiredRouter router = app.router(profile());
                HttpResponse resp = router.route(post("/v1/messages", "{}"));

                assertEquals(200, resp.status);
                assertEquals("stub-ok:m-stub", resp.body,
                        "the request should have been dispatched to the jar-loaded StubProvider, carrying the assigned model");
            }
        }
    }

    @Test
    void fromDirectory_keepsClassLoaderOpen_soHandlerCanLazilyLoadAHelperClass(@TempDir Path tmp) throws IOException {
        Path providersDir = tmp.resolve("providers");
        Files.createDirectory(providersDir);
        writeJarOnlyProviderJar(providersDir.resolve("jar-only-provider.jar"), tmp.resolve("compile-work"));

        try (ProviderRegistry registry = ProviderRegistry.fromDirectory(providersDir)) {
            assertEquals(List.of("jaronly"), registry.listProviderIds());

            Store store = Storage.memory();
            seedModelMapFor(store, "jaronly", "m-jaronly");

            try (AiJava app = AiJava.builder().storage(store).providersDir(providersDir).build()) {
                assertEquals(List.of("jaronly"), app.providerRegistry().listProviderIds());

                AiJava.WiredRouter router = app.router(profileFor("jaronly"));

                // JarOnlyHelper is referenced ONLY from inside JarOnlyProvider.handle(...) below,
                // never during construction/ServiceLoader instantiation -- the JVM resolves it
                // lazily, on this very call. A regression that closes ProviderRegistry's
                // URLClassLoader right after discovery (instead of keeping it open for the
                // registry's lifetime) makes this throw NoClassDefFoundError instead of
                // returning the expected response.
                HttpResponse resp = router.route(post("/v1/messages", "{}"));

                assertEquals(200, resp.status);
                assertEquals("jaronly-ok:m-jaronly", resp.body,
                        "the request should have been dispatched to the jar-only JarOnlyProvider, "
                                + "which itself delegated to a helper class loadable only through the still-open jar classloader");
            }
        }
    }

    @Test
    void get_returnsTheDiscoveredProviderInstanceById_orNullWhenUnknown(@TempDir Path tmp) throws IOException {
        Path providersDir = tmp.resolve("providers");
        Files.createDirectory(providersDir);
        writeStubProviderJar(providersDir.resolve("stub-provider.jar"));

        try (ProviderRegistry registry = ProviderRegistry.fromDirectory(providersDir)) {
            Provider found = registry.get("stub");
            assertEquals("stub", found.id());
            assertEquals(null, registry.get("nope"));
        }
    }

    @Test
    void jarFor_attributesDiscoveredProviderToItsOwnJarFile(@TempDir Path tmp) throws IOException {
        Path providersDir = tmp.resolve("providers");
        Files.createDirectory(providersDir);
        Path jarPath = providersDir.resolve("stub-provider.jar");
        writeStubProviderJar(jarPath);

        try (ProviderRegistry registry = ProviderRegistry.fromDirectory(providersDir)) {
            assertEquals(jarPath, registry.jarFor("stub"));
            assertEquals(null, registry.jarFor("nope"));
        }
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
        return profileFor("stub");
    }

    private static RoutingProfile profileFor(String tierSourceProvider) {
        RoutingProfile p = new RoutingProfile();
        p.configFile = CONFIG_FILE;
        p.routingKey = "providerRouting";
        p.tierSourceProvider = tierSourceProvider;
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
        seedModelMapFor(store, "stub", "m-stub");
    }

    private static void seedModelMapFor(Store store, String provider, String model) {
        GsonJsonCodec json = new GsonJsonCodec();
        Map<String, Object> assignment = new HashMap<>();
        assignment.put("provider", provider);
        assignment.put("model", model);
        Map<String, Object> doc = new HashMap<>();
        doc.put("modelMap", Collections.singletonMap("opus", List.of(assignment)));
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

    // -- jar-only provider (compiled at test runtime, never on this test's own classpath) -----

    private static final String JAR_ONLY_PACKAGE = "io.github.intisy.ai.jvm.jaronly";

    private static final String JAR_ONLY_PROVIDER_SOURCE =
            "package " + JAR_ONLY_PACKAGE + ";\n"
            + "import io.github.intisy.ai.shared.routing.HandlerCtx;\n"
            + "import io.github.intisy.ai.shared.routing.Provider;\n"
            + "import io.github.intisy.ai.shared.spi.http.HttpRequest;\n"
            + "import io.github.intisy.ai.shared.spi.http.HttpResponse;\n"
            + "public final class JarOnlyProvider implements Provider {\n"
            + "    @Override public String id() { return \"jaronly\"; }\n"
            + "    @Override public HttpResponse handle(HttpRequest req, HandlerCtx ctx) {\n"
            // JarOnlyHelper is referenced ONLY here, never during construction/ServiceLoader
            // instantiation, so the JVM resolves (and thus needs to load) it lazily, the first
            // time handle(...) actually runs -- exactly the code path Finding 1 breaks.
            + "        return JarOnlyHelper.respond(ctx.model);\n"
            + "    }\n"
            + "}\n";

    private static final String JAR_ONLY_HELPER_SOURCE =
            "package " + JAR_ONLY_PACKAGE + ";\n"
            + "import io.github.intisy.ai.shared.spi.http.HttpResponse;\n"
            + "import java.util.HashMap;\n"
            + "final class JarOnlyHelper {\n"
            + "    private JarOnlyHelper() {}\n"
            + "    static HttpResponse respond(String model) {\n"
            + "        HttpResponse resp = new HttpResponse();\n"
            + "        resp.status = 200;\n"
            + "        resp.headers = new HashMap<>();\n"
            + "        resp.body = \"jaronly-ok:\" + model;\n"
            + "        return resp;\n"
            + "    }\n"
            + "}\n";

    /**
     * Compiles {@code JarOnlyProvider}/{@code JarOnlyHelper} (source above) with the JDK's own
     * compiler into a scratch directory that is NOT on this test's compile/runtime classpath,
     * then jars only that compiled output plus a real {@code META-INF/services} registration.
     * Unlike {@link #writeStubProviderJar}, nothing here is reachable through the test's own
     * {@link ClassLoader} -- {@link ProviderRegistry#fromDirectory}'s {@code URLClassLoader} is
     * the ONLY path that can ever resolve these two classes.
     */
    private static void writeJarOnlyProviderJar(Path jarPath, Path workDir) throws IOException {
        Path srcDir = workDir.resolve("src").resolve(JAR_ONLY_PACKAGE.replace('.', '/'));
        Files.createDirectories(srcDir);
        Path providerSrc = srcDir.resolve("JarOnlyProvider.java");
        Path helperSrc = srcDir.resolve("JarOnlyHelper.java");
        Files.write(providerSrc, JAR_ONLY_PROVIDER_SOURCE.getBytes(StandardCharsets.UTF_8));
        Files.write(helperSrc, JAR_ONLY_HELPER_SOURCE.getBytes(StandardCharsets.UTF_8));

        Path classesDir = workDir.resolve("classes");
        Files.createDirectories(classesDir);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException(
                    "no system Java compiler available -- run this test on a JDK, not a JRE");
        }
        int result = compiler.run(null, null, null,
                "-d", classesDir.toString(),
                "-cp", jarOnlyProviderCompileClasspath(),
                providerSrc.toString(), helperSrc.toString());
        if (result != 0) {
            throw new IllegalStateException("failed to compile jar-only provider fixture sources");
        }

        Path classFilesDir = classesDir.resolve(JAR_ONLY_PACKAGE.replace('.', '/'));
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(jarPath))) {
            writeClassEntry(jar, classFilesDir.resolve("JarOnlyProvider.class"));
            writeClassEntry(jar, classFilesDir.resolve("JarOnlyHelper.class"));

            jar.putNextEntry(new JarEntry("META-INF/services/" + Provider.class.getName()));
            jar.write((JAR_ONLY_PACKAGE + ".JarOnlyProvider").getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }

    /**
     * Builds a compile classpath from the actual on-disk code source of every type the fixture
     * sources reference (besides JDK types), rather than trusting {@code java.class.path} --
     * Gradle test workers sometimes route the runtime classpath through a manifest-{@code
     * Class-Path} indirection jar (a workaround for Windows' command-line length limit), in
     * which case {@code java.class.path} alone would NOT list the jar/dir actually containing
     * these classes and {@code javac} would fail to resolve them.
     */
    private static String jarOnlyProviderCompileClasspath() throws IOException {
        LinkedHashSet<String> entries = new LinkedHashSet<>();
        for (Class<?> c : new Class<?>[] {Provider.class, HttpRequest.class, HttpResponse.class, HandlerCtx.class}) {
            CodeSource cs = c.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) {
                throw new IllegalStateException(
                        "no code source for " + c + " -- cannot build a compile classpath for the jar-only provider fixture");
            }
            try {
                entries.add(new File(cs.getLocation().toURI()).getAbsolutePath());
            } catch (URISyntaxException e) {
                throw new IOException(e);
            }
        }
        return String.join(File.pathSeparator, entries);
    }

    private static void writeClassEntry(JarOutputStream jar, Path classFile) throws IOException {
        String entryName = JAR_ONLY_PACKAGE.replace('.', '/') + "/" + classFile.getFileName();
        jar.putNextEntry(new JarEntry(entryName));
        jar.write(Files.readAllBytes(classFile));
        jar.closeEntry();
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
