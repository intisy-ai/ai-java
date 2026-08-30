package io.github.intisy.ai.exampleserver;

import io.github.intisy.ai.seam.NoopLogger;
import io.github.intisy.ai.exampleserver.admin.MessagesAdmin;
import io.github.intisy.ai.exampleserver.discovery.ProviderDiscovery;
import io.github.intisy.ai.exampleserver.discovery.ProviderRegistryHolder;
import io.github.intisy.ai.ir.IrRequest;
import io.github.intisy.ai.ir.IrResponse;
import io.github.intisy.ai.ir.spi.StreamDecoder;
import io.github.intisy.ai.ir.spi.StreamEncoder;
import io.github.intisy.ai.ir.spi.Translator;
import io.github.intisy.ai.seam.jvm.GsonJsonCodec;
import io.github.intisy.ai.seam.InMemoryStore;
import io.github.intisy.ai.api.seam.JsonCodec;
import io.github.intisy.ai.api.seam.Store;
import io.github.intisy.ai.api.seam.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Exercises {@link MessagesAdmin} against the same REAL jar-discovered {@code echo} provider
 * {@link ConfigAdminTest}/{@link QuotaAdminTest} use, staged the same way: a DIRECT {@code
 * Provider#handle} call, no router, no model-&gt;provider resolution. {@code EchoProvider} answers
 * with an Anthropic-messages-shaped body echoing back {@code HandlerCtx#model}, giving a real
 * round-trip with no fabricated HTTP request and no network involved.
 */
class MessagesAdminTest {

    private Store store;
    private JsonCodec json;
    private ProviderRegistryHolder holder;
    private MessagesAdmin messages;

    @BeforeEach
    void setUp(@TempDir Path providersDir) throws IOException {
        store = new InMemoryStore();
        json = new GsonJsonCodec();

        stageProviderJar(providersDir);
        holder = new ProviderRegistryHolder(TestPlugins.create(), ProviderDiscovery.resolve(providersDir));
        assertTrue(holder.listProviderIds().contains("echo"), holder.listProviderIds().toString());

        messages = new MessagesAdmin(store, json, holder, NoopLogger.INSTANCE);
    }

    @AfterEach
    void tearDown() throws IOException {
        // Releases the URLClassLoader backing the jar copied into @TempDir, or its cleanup fails
        // on Windows (file still in use) -- same reasoning as ConfigAdminTest/QuotaAdminTest.
        if (holder != null && holder.get() != null) holder.get().close();
    }

    private static void stageProviderJar(Path targetDir) throws IOException {
        String staged = System.getProperty("exampleserver.providersDir");
        assertNotNull(staged, "exampleserver.providersDir must be set by the Gradle test task");
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get(staged), "*.jar")) {
            for (Path jar : stream) {
                Files.copy(jar, targetDir.resolve(jar.getFileName()));
                return;
            }
        }
        fail("no staged provider jar found in " + staged);
    }

    @Test
    void sendCallsProviderDirectlyAndThreadsModelThroughHandlerCtx() {
        HttpResponse resp = messages.send("echo", "{\"model\":\"m-echo-haiku\",\"messages\":[]}");
        assertEquals(200, resp.status);
        assertTrue(resp.body.contains("Echo provider handled your request"), resp.body);
        assertTrue(resp.body.contains("m-echo-haiku"), resp.body);
    }

    @Test
    void sendWithMalformedBodyStillReachesProvider() {
        // EchoProvider never parses the body itself -- a malformed body must not block the DIRECT
        // call; modelOf() degrades to null, and the provider serves its own default.
        HttpResponse resp = messages.send("echo", "not json");
        assertEquals(200, resp.status);
        assertTrue(resp.body.contains("echo-default"), resp.body);
    }

    @Test
    void sendUnknownProviderIsAnthropicShaped404() {
        HttpResponse resp = messages.send("does-not-exist", "{\"model\":\"x\",\"messages\":[]}");
        assertEquals(404, resp.status);
        assertTrue(resp.body.contains("\"type\":\"error\""), resp.body);
        assertTrue(resp.body.contains("not_found"), resp.body);
        assertTrue(resp.body.contains("does-not-exist"), resp.body);
    }

    // A provider whose handle() throws an Error (not an Exception) -- e.g. a real upstream path
    // hitting a LinkageError/NoClassDefFoundError -- must still degrade to a readable 502 JSON
    // error, not propagate out of send() and drop the connection. ThrowingProvider (packaged in
    // the same staged jar as EchoProvider) always throws NoClassDefFoundError from handle().
    @Test
    void sendSurvivesAProviderThrowingAnError() {
        HttpResponse resp = messages.send("throwing", "{\"model\":\"x\",\"messages\":[]}");
        assertEquals(502, resp.status);
        assertTrue(resp.body.contains("\"type\":\"error\""), resp.body);
        assertTrue(resp.body.contains("api_error"), resp.body);
        assertTrue(resp.body.contains("NoClassDefFoundError"), resp.body);
        assertTrue(resp.body.contains("simulated upstream classloader failure"), resp.body);
    }

    // A provider whose handleIr() THROWS HandleIrException (a typed transport error carrying a
    // real upstream status/headers/body/retryAfterMs) -- HandleIrRateLimitedProvider (packaged in
    // the same staged jar) always throws a 429 this way. The response must preserve that status
    // and header/body, not collapse to a flat 502.
    @Test
    void sendPreservesStatusHeadersAndBodyFromAThrownHandleIrException() {
        HttpResponse resp = messages.send("handleir-ratelimited", "{\"model\":\"x\",\"messages\":[]}");
        assertEquals(429, resp.status);
        assertEquals("7", resp.headers.get("retry-after"), resp.headers.toString());
        assertEquals("45000", resp.headers.get("x-hub-retry-after-ms"), resp.headers.toString());
        assertEquals("{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\","
                + "\"message\":\"handleIr rate limited\"}}", resp.body);
    }

    // HandleIrPresetRetryHeaderProvider's HandleIrException already sets x-hub-retry-after-ms
    // itself; the reconstruction must leave that value alone rather than overwriting it with its
    // own retryAfterMs (a different value here, so a clobber would be caught).
    @Test
    void sendDoesNotOverwriteAnAlreadyPresentRetryAfterMsHeader() {
        HttpResponse resp = messages.send("handleir-retry-header-preset", "{\"model\":\"x\",\"messages\":[]}");
        assertEquals(429, resp.status);
        assertEquals("999", resp.headers.get("x-hub-retry-after-ms"), resp.headers.toString());
    }

    // MessagesAdmin resolves its translator fresh per instance (see resolveTranslator()'s
    // @implNote), so pointing exampleserver.translatorsDir at an empty directory before
    // constructing a SEPARATE instance here is enough to force the no-translator path, with no
    // risk of a stale cache from another test's instance.
    @Test
    void sendWithNoTranslatorStagedIs503(@TempDir Path emptyTranslatorsDir) {
        String previous = System.getProperty("exampleserver.translatorsDir");
        System.setProperty("exampleserver.translatorsDir", emptyTranslatorsDir.toString());
        try {
            MessagesAdmin noTranslator = new MessagesAdmin(store, json, holder, NoopLogger.INSTANCE);
            HttpResponse resp = noTranslator.send("echo", "{\"model\":\"x\",\"messages\":[]}");
            assertEquals(503, resp.status);
            assertTrue(resp.body.contains("\"type\":\"error\""), resp.body);
            assertTrue(resp.body.contains("no_translator"), resp.body);
        } finally {
            if (previous != null) {
                System.setProperty("exampleserver.translatorsDir", previous);
            } else {
                System.clearProperty("exampleserver.translatorsDir");
            }
        }
    }

    // File.listFiles order is unspecified, so with two translator jars staged, picking one
    // arbitrarily would be a silent, nondeterministic misconfiguration. Two DISTINCT Translator
    // implementations are staged here (StubTranslatorOne/Two below) -- ServiceLoader dedupes by
    // fully-qualified class name, so two jars registering the SAME class would prove nothing.
    // CleanupMode.NEVER: MessagesAdmin never exposes the TranslatorRegistry it resolves its
    // translator from (unlike ProviderRegistryHolder, which tearDown() explicitly closes), so its
    // URLClassLoader over one.jar/two.jar stays open past this test -- on Windows that leaves both
    // jars locked, and JUnit's default @TempDir cleanup would fail trying to delete them.
    @Test
    void sendWithAmbiguousTranslatorsStagedIs503(@TempDir(cleanup = CleanupMode.NEVER) Path ambiguousTranslatorsDir) throws IOException {
        writeTranslatorJar(ambiguousTranslatorsDir.resolve("one.jar"), StubTranslatorOne.class);
        writeTranslatorJar(ambiguousTranslatorsDir.resolve("two.jar"), StubTranslatorTwo.class);

        String previous = System.getProperty("exampleserver.translatorsDir");
        System.setProperty("exampleserver.translatorsDir", ambiguousTranslatorsDir.toString());
        try {
            MessagesAdmin ambiguous = new MessagesAdmin(store, json, holder, NoopLogger.INSTANCE);
            HttpResponse resp = ambiguous.send("echo", "{\"model\":\"x\",\"messages\":[]}");
            assertEquals(503, resp.status);
            assertTrue(resp.body.contains("\"type\":\"error\""), resp.body);
            assertTrue(resp.body.contains("ambiguous_translator"), resp.body);
            assertTrue(resp.body.contains("found 2 Translator implementations"), resp.body);
            // Compare by directory name only, not the full absolute path string: File#getAbsolutePath
            // (used by the production code) and Path#toAbsolutePath (used here) can render the same
            // @TempDir differently on Windows (e.g. real-path resolution), even though both name it.
            assertTrue(resp.body.contains(ambiguousTranslatorsDir.getFileName().toString()), resp.body);
        } finally {
            if (previous != null) {
                System.setProperty("exampleserver.translatorsDir", previous);
            } else {
                System.clearProperty("exampleserver.translatorsDir");
            }
        }
    }

    private static void writeTranslatorJar(Path jarPath, Class<? extends Translator> translatorClass) throws IOException {
        String className = translatorClass.getName();
        String classResourcePath = className.replace('.', '/') + ".class";
        byte[] classBytes = readClassBytes(classResourcePath);

        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(jarPath))) {
            jar.putNextEntry(new JarEntry(classResourcePath));
            jar.write(classBytes);
            jar.closeEntry();

            jar.putNextEntry(new JarEntry("META-INF/services/" + Translator.class.getName()));
            jar.write(className.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }

    private static byte[] readClassBytes(String classResourcePath) throws IOException {
        try (InputStream in = MessagesAdminTest.class.getClassLoader().getResourceAsStream(classResourcePath)) {
            if (in == null) throw new IllegalStateException("missing compiled class on test classpath: " + classResourcePath);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** Minimal, distinct {@link Translator} fixtures used only to prove ambiguous discovery. */
    public static final class StubTranslatorOne implements Translator {
        @Override
        public IrRequest decodeRequest(String wireJson) {
            return new IrRequest();
        }

        @Override
        public String encodeRequest(IrRequest request) {
            return "{}";
        }

        @Override
        public IrResponse decodeResponse(String wireJson) {
            return new IrResponse();
        }

        @Override
        public String encodeResponse(IrResponse response) {
            return "{}";
        }

        @Override
        public StreamDecoder newStreamDecoder() {
            return chunk -> Collections.emptyList();
        }

        @Override
        public StreamEncoder newStreamEncoder() {
            return event -> "";
        }
    }

    public static final class StubTranslatorTwo implements Translator {
        @Override
        public IrRequest decodeRequest(String wireJson) {
            return new IrRequest();
        }

        @Override
        public String encodeRequest(IrRequest request) {
            return "{}";
        }

        @Override
        public IrResponse decodeResponse(String wireJson) {
            return new IrResponse();
        }

        @Override
        public String encodeResponse(IrResponse response) {
            return "{}";
        }

        @Override
        public StreamDecoder newStreamDecoder() {
            return chunk -> Collections.emptyList();
        }

        @Override
        public StreamEncoder newStreamEncoder() {
            return event -> "";
        }
    }
}
