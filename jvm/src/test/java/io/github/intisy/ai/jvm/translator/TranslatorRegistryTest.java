package io.github.intisy.ai.jvm.translator;

import io.github.intisy.ai.ir.IrRequest;
import io.github.intisy.ai.ir.IrResponse;
import io.github.intisy.ai.ir.spi.StreamDecoder;
import io.github.intisy.ai.ir.spi.StreamEncoder;
import io.github.intisy.ai.ir.spi.Translator;
import io.github.intisy.ai.ir.stream.IrStreamEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Proves the {@code ServiceLoader}-based {@link TranslatorRegistry} discovers a real
 * {@link Translator} jar on disk, mirroring {@code ProviderRegistryTest}'s jar-packaging shape
 * one level up for the translator SPI.
 */
class TranslatorRegistryTest {

    @Test
    void fromDirectory_discoversJarTranslator_andEncodeRequestRoundTrips(@TempDir Path tmp) throws IOException {
        Path translatorsDir = tmp.resolve("translators");
        Files.createDirectory(translatorsDir);
        writeStubTranslatorJar(translatorsDir.resolve("stub-translator.jar"));

        TranslatorRegistry registry = TranslatorRegistry.fromDirectory(translatorsDir);
        try {
            assertEquals(1, registry.translators().size());

            Translator translator = registry.translators().get(0);
            IrRequest request = new IrRequest();
            request.model = StubTranslator.MODEL;

            String wire = translator.encodeRequest(request);
            IrRequest decoded = translator.decodeRequest(wire);
            assertEquals(StubTranslator.MODEL, decoded.model);
        } finally {
            registry.close();
        }
    }

    /**
     * Windows-only signal, kept as a cheap smoke check but NOT the sole proof of the fix: an
     * open {@link URLClassLoader} handle on Windows blocks deleting the jar it was opened from,
     * so the delete below only succeeds if {@link TranslatorRegistry#close()} actually released
     * it. On Linux, unlinking a still-open file succeeds regardless, so this test cannot fail
     * there even with the release broken -- see
     * {@link #close_releasesTheLoader_soALazyInnerClassCannotBeDefinedAfterwards} for the
     * platform-independent proof.
     */
    @Test
    void close_releasesTheJarHandle_soTheJarCanBeDeleted(@TempDir Path tmp) throws IOException {
        Path dir = tmp.resolve("translators");
        Files.createDirectory(dir);
        Path jar = dir.resolve("stub-translator.jar");
        writeStubTranslatorJar(jar);

        TranslatorRegistry registry = TranslatorRegistry.fromDirectory(dir);
        assertEquals(1, registry.translators().size());
        registry.close();

        Files.delete(jar);
    }

    /**
     * Platform-independent proof, unlike {@link #close_releasesTheJarHandle_soTheJarCanBeDeleted}
     * above: a closed {@link URLClassLoader} can still serve classes it already defined, but can
     * never define a NEW one, and that is observable on every OS. {@link
     * #writeJarOnlyTranslatorJar} compiles {@code JarOnlyTranslator} fresh into a scratch
     * directory never on this test's own classpath, so the only loader that can ever resolve it
     * (or the anonymous {@code StreamDecoder} its {@code newStreamDecoder()} defines lazily on
     * first call, mirroring {@code EchoTranslator}'s own {@code $1}/{@code $2} anonymous inner
     * classes) is the loader {@link TranslatorRegistry#fromDirectory} built for the directory it
     * came from -- a nested class of THIS test (like {@link StubTranslator}) would be
     * parent-resolvable and prove nothing, per {@code ProviderRegistryTest}'s equivalent jar-only
     * fixture.
     */
    @Test
    void close_releasesTheLoader_soALazyInnerClassCannotBeDefinedAfterwards(@TempDir Path tmp) throws IOException {
        Path dir = tmp.resolve("translators");
        Files.createDirectory(dir);
        writeJarOnlyTranslatorJar(dir.resolve("jar-only-translator.jar"), tmp.resolve("compile-work"));

        TranslatorRegistry registry = TranslatorRegistry.fromDirectory(dir);
        assertEquals(1, registry.translators().size());
        Translator jarOnlyTranslator = registry.translators().get(0);

        registry.close();

        assertThrows(NoClassDefFoundError.class, jarOnlyTranslator::newStreamDecoder,
                "closing this registry's loader should prevent it from defining the "
                        + "lazily-referenced anonymous StreamDecoder it hasn't touched yet");
    }

    /**
     * The defect a shared static loader had: two INDEPENDENT callers discovering the SAME
     * directory must not be able to break each other. Each {@link TranslatorRegistry#fromDirectory}
     * call opens its OWN {@link URLClassLoader} over its own copy of the directory's jars, so
     * closing one registry must leave a translator obtained from a DIFFERENT registry (even one
     * built from the identical directory) fully able to resolve a class it hasn't touched yet.
     */
    @Test
    void twoRegistriesFromTheSameDirectory_closingOneDoesNotAffectTheOther(@TempDir Path tmp) throws IOException {
        Path dir = tmp.resolve("translators");
        Files.createDirectory(dir);
        writeJarOnlyTranslatorJar(dir.resolve("jar-only-translator.jar"), tmp.resolve("compile-work"));

        TranslatorRegistry registry1 = TranslatorRegistry.fromDirectory(dir);
        TranslatorRegistry registry2 = TranslatorRegistry.fromDirectory(dir);
        assertEquals(1, registry1.translators().size());
        assertEquals(1, registry2.translators().size());
        Translator translator2 = registry2.translators().get(0);

        registry1.close();

        try {
            assertDoesNotThrow(translator2::newStreamDecoder,
                    "closing registry1's loader must not affect registry2's, even though both "
                            + "were built from the same directory");
        } finally {
            registry2.close();
        }
    }

    private static void writeStubTranslatorJar(Path jarPath) throws IOException {
        String className = StubTranslator.class.getName();
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
        try (InputStream in = TranslatorRegistryTest.class.getClassLoader().getResourceAsStream(classResourcePath)) {
            if (in == null) throw new IllegalStateException("missing compiled class on test classpath: " + classResourcePath);
            ByteArrayOutputStream classBytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                classBytes.write(buffer, 0, read);
            }
            return classBytes.toByteArray();
        }
    }

    // -- jar-only translator (compiled at test runtime, never on this test's own classpath) ----

    private static final String JAR_ONLY_TRANSLATOR_PACKAGE = "io.github.intisy.ai.jvm.jaronlytranslator";

    private static final String JAR_ONLY_TRANSLATOR_SOURCE =
            "package " + JAR_ONLY_TRANSLATOR_PACKAGE + ";\n"
            + "import io.github.intisy.ai.ir.IrRequest;\n"
            + "import io.github.intisy.ai.ir.IrResponse;\n"
            + "import io.github.intisy.ai.ir.spi.StreamDecoder;\n"
            + "import io.github.intisy.ai.ir.spi.StreamEncoder;\n"
            + "import io.github.intisy.ai.ir.spi.Translator;\n"
            + "import io.github.intisy.ai.ir.stream.IrStreamEvent;\n"
            + "import java.util.Collections;\n"
            + "import java.util.List;\n"
            + "public final class JarOnlyTranslator implements Translator {\n"
            + "    static final String MODEL = \"jar-only-translator-model\";\n"
            + "    @Override public IrRequest decodeRequest(String wireJson) {\n"
            + "        IrRequest request = new IrRequest();\n"
            + "        request.model = MODEL;\n"
            + "        return request;\n"
            + "    }\n"
            + "    @Override public String encodeRequest(IrRequest request) {\n"
            + "        return \"{\\\"model\\\":\\\"\" + MODEL + \"\\\"}\";\n"
            + "    }\n"
            + "    @Override public IrResponse decodeResponse(String wireJson) {\n"
            + "        IrResponse response = new IrResponse();\n"
            + "        response.model = MODEL;\n"
            + "        return response;\n"
            + "    }\n"
            + "    @Override public String encodeResponse(IrResponse response) {\n"
            + "        return \"{\\\"model\\\":\\\"\" + MODEL + \"\\\"}\";\n"
            + "    }\n"
            // The anonymous StreamDecoder below is referenced ONLY here, never during
            // ServiceLoader construction, so the JVM resolves (and needs to define)
            // JarOnlyTranslator$1 lazily, the first time this method actually runs.
            + "    @Override public StreamDecoder newStreamDecoder() {\n"
            + "        return new StreamDecoder() {\n"
            + "            @Override public List<IrStreamEvent> decode(String chunk) {\n"
            + "                return Collections.emptyList();\n"
            + "            }\n"
            + "        };\n"
            + "    }\n"
            + "    @Override public StreamEncoder newStreamEncoder() {\n"
            + "        return new StreamEncoder() {\n"
            + "            @Override public String encode(IrStreamEvent event) {\n"
            + "                return \"\";\n"
            + "            }\n"
            + "        };\n"
            + "    }\n"
            + "}\n";

    /**
     * Compiles {@code JarOnlyTranslator} (source above) with the JDK's own compiler into a
     * scratch directory that is NOT on this test's compile/runtime classpath, then jars only that
     * compiled output (the outer class plus its anonymous inner classes) plus a real {@code
     * META-INF/services} registration. Mirrors {@code ProviderRegistryTest.writeJarOnlyProviderJar}
     * one level up for the translator SPI.
     */
    private static void writeJarOnlyTranslatorJar(Path jarPath, Path workDir) throws IOException {
        Path srcDir = workDir.resolve("src").resolve(JAR_ONLY_TRANSLATOR_PACKAGE.replace('.', '/'));
        Files.createDirectories(srcDir);
        Path translatorSrc = srcDir.resolve("JarOnlyTranslator.java");
        Files.write(translatorSrc, JAR_ONLY_TRANSLATOR_SOURCE.getBytes(StandardCharsets.UTF_8));

        Path classesDir = workDir.resolve("classes");
        Files.createDirectories(classesDir);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException(
                    "no system Java compiler available -- run this test on a JDK, not a JRE");
        }
        LinkedHashSet<String> cp = new LinkedHashSet<>();
        for (Class<?> c : new Class<?>[] {
                Translator.class, IrRequest.class, IrResponse.class, StreamDecoder.class, StreamEncoder.class, IrStreamEvent.class}) {
            cp.add(codeSourcePath(c));
        }
        int result = compiler.run(null, null, null,
                "-d", classesDir.toString(),
                "-cp", String.join(File.pathSeparator, cp),
                translatorSrc.toString());
        if (result != 0) {
            throw new IllegalStateException("failed to compile jar-only translator fixture source");
        }

        Path classFilesDir = classesDir.resolve(JAR_ONLY_TRANSLATOR_PACKAGE.replace('.', '/'));
        File[] compiledClassFiles = classFilesDir.toFile()
                .listFiles((dir, name) -> name.startsWith("JarOnlyTranslator") && name.endsWith(".class"));
        if (compiledClassFiles == null || compiledClassFiles.length == 0) {
            throw new IllegalStateException("compiling the jar-only translator fixture produced no .class files");
        }

        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(jarPath))) {
            for (File classFile : compiledClassFiles) {
                String entryName = JAR_ONLY_TRANSLATOR_PACKAGE.replace('.', '/') + "/" + classFile.getName();
                jar.putNextEntry(new JarEntry(entryName));
                jar.write(Files.readAllBytes(classFile.toPath()));
                jar.closeEntry();
            }

            jar.putNextEntry(new JarEntry("META-INF/services/" + Translator.class.getName()));
            jar.write((JAR_ONLY_TRANSLATOR_PACKAGE + ".JarOnlyTranslator").getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }

    private static String codeSourcePath(Class<?> c) throws IOException {
        CodeSource cs = c.getProtectionDomain().getCodeSource();
        if (cs == null || cs.getLocation() == null) {
            throw new IllegalStateException("no code source for " + c + " -- cannot build a compile classpath");
        }
        try {
            return new File(cs.getLocation().toURI()).getAbsolutePath();
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }
    }

    /** Minimal {@link Translator} used only to prove {@link TranslatorRegistry} discovery. */
    public static final class StubTranslator implements Translator {
        static final String MODEL = "stub-translator-model";

        @Override
        public IrRequest decodeRequest(String wireJson) {
            IrRequest request = new IrRequest();
            request.model = MODEL;
            return request;
        }

        @Override
        public String encodeRequest(IrRequest request) {
            return "{\"model\":\"" + MODEL + "\"}";
        }

        @Override
        public IrResponse decodeResponse(String wireJson) {
            IrResponse response = new IrResponse();
            response.model = MODEL;
            return response;
        }

        @Override
        public String encodeResponse(IrResponse response) {
            return "{\"model\":\"" + MODEL + "\"}";
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
