package io.github.intisy.ai.jvm.translator;

import io.github.intisy.ai.ir.IrRequest;
import io.github.intisy.ai.ir.IrResponse;
import io.github.intisy.ai.ir.spi.StreamDecoder;
import io.github.intisy.ai.ir.spi.StreamEncoder;
import io.github.intisy.ai.ir.spi.Translator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves the {@code ServiceLoader}-based {@link TranslatorRegistry} discovers a real
 * {@link Translator} jar on disk, mirroring {@code ProviderRegistryTest}'s jar-packaging shape
 * one level up for the translator SPI.
 */
class TranslatorRegistryTest {

    @Test
    void load_discoversJarTranslator_andEncodeRequestRoundTrips(@TempDir Path tmp) throws IOException {
        Path translatorsDir = tmp.resolve("translators");
        Files.createDirectory(translatorsDir);
        writeStubTranslatorJar(translatorsDir.resolve("stub-translator.jar"));

        List<Translator> translators = TranslatorRegistry.load(translatorsDir.toFile());
        try {
            assertEquals(1, translators.size());

            Translator translator = translators.get(0);
            IrRequest request = new IrRequest();
            request.model = StubTranslator.MODEL;

            String wire = translator.encodeRequest(request);
            IrRequest decoded = translator.decodeRequest(wire);
            assertEquals(StubTranslator.MODEL, decoded.model);
        } finally {
            TranslatorRegistry.close();
        }
    }

    @Test
    void load_calledAgain_closesThePreviousClassLoader_soTheOldJarCanBeDeleted(@TempDir Path tmp) throws IOException {
        Path firstDir = tmp.resolve("translators-1");
        Path secondDir = tmp.resolve("translators-2");
        Files.createDirectory(firstDir);
        Files.createDirectory(secondDir);
        Path firstJar = firstDir.resolve("stub-translator.jar");
        writeStubTranslatorJar(firstJar);
        writeStubTranslatorJar(secondDir.resolve("stub-translator.jar"));

        try {
            assertEquals(1, TranslatorRegistry.load(firstDir.toFile()).size());
            assertEquals(1, TranslatorRegistry.load(secondDir.toFile()).size());

            // An open URLClassLoader handle on Windows blocks deleting the jar it was opened
            // from; this delete only succeeds if the first load()'s loader was closed before the
            // second load() replaced it, rather than being overwritten and leaked.
            Files.delete(firstJar);
        } finally {
            TranslatorRegistry.close();
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
            return in.readAllBytes();
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
