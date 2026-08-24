package io.github.intisy.ai.exampleserver;

import io.github.intisy.ai.ir.Block;
import io.github.intisy.ai.ir.IrRequest;
import io.github.intisy.ai.ir.IrResponse;
import io.github.intisy.ai.ir.TextBlock;
import io.github.intisy.ai.ir.spi.Translator;
import io.github.intisy.ai.jvm.translator.TranslatorRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Proves the REAL {@code :examples-translator} jar (staged by Gradle's {@code stageTranslators}
 * task, see build.gradle) is discoverable via {@link TranslatorRegistry} and its {@code
 * EchoTranslator} actually runs -- {@code TranslatorRegistryTest} (in {@code :jvm}) only proves
 * discovery against an inline stub fixture, never the real jar {@code ServerProfile}/{@code
 * MessagesAdmin} depend on. Mirrors {@link ProviderDiscoveryTest}'s staged-jar-copy shape.
 */
class TranslatorDiscoveryTest {

    @Test
    void discoversAndRunsTheRealEchoTranslatorJar(@TempDir Path dir) throws IOException {
        String staged = System.getProperty("exampleserver.translatorsDir");
        assertNotNull(staged, "exampleserver.translatorsDir must be set by the Gradle test task");
        Path src = null;
        for (Path p : (Iterable<Path>) Files.list(Paths.get(staged))::iterator) {
            if (p.getFileName().toString().endsWith(".jar")) { src = p; break; }
        }
        assertNotNull(src, "a translator jar must be staged");
        Files.copy(src, dir.resolve(src.getFileName()));

        try (TranslatorRegistry registry = TranslatorRegistry.fromDirectory(dir)) {
            assertEquals(1, registry.translators().size(), registry.translators().toString());
            Translator translator = registry.translators().get(0);

            IrRequest decoded = translator.decodeRequest("{\"model\":\"whatever-the-caller-sent\"}");
            assertEquals("whatever-the-caller-sent", decoded.model);

            IrRequest modelless = translator.decodeRequest("{}");
            assertEquals("echo-model", modelless.model, "falls back to its own id when the wire names no model");

            IrResponse response = new IrResponse();
            response.id = "some-id";
            response.model = "some-other-model";
            response.content = Collections.<Block>singletonList(new TextBlock("served text"));
            assertEquals("{\"id\":\"some-id\",\"model\":\"some-other-model\",\"text\":\"served text\"}",
                    translator.encodeResponse(response));
        }
    }
}
