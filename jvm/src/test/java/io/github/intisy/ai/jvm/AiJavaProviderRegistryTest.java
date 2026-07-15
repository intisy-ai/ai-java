package io.github.intisy.ai.jvm;

import io.github.intisy.ai.jvm.backend.store.InMemoryStore;
import io.github.intisy.ai.jvm.provider.ProviderRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiJavaProviderRegistryTest {

    @Test
    void injectedRegistryIsUsed() throws IOException {
        ProviderRegistry registry = ProviderRegistry.empty();
        try (AiJava ai = AiJava.builder().storage(new InMemoryStore()).providerRegistry(registry).build()) {
            assertSame(registry, ai.providerRegistry());
        }
    }

    @Test
    void registryAndDirTogetherThrow() {
        assertThrows(IllegalStateException.class, () ->
                AiJava.builder()
                        .storage(new InMemoryStore())
                        .providerRegistry(ProviderRegistry.empty())
                        .providersDir(Paths.get("providers"))
                        .build());
    }
}
