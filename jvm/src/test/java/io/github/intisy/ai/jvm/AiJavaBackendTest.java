package io.github.intisy.ai.jvm;

import io.github.intisy.ai.jvm.backend.Backend;
import io.github.intisy.ai.jvm.backend.json.GsonJsonCodec;
import io.github.intisy.ai.jvm.backend.store.InMemoryStore;
import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.Store;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiJavaBackendTest {

    @Test
    void backendSuppliesAllSpis() throws IOException {
        Store store = new InMemoryStore();
        Backend backend = Backend.builder().store(store).build();
        try (AiJava ai = AiJava.builder().backend(backend).build()) {
            assertSame(store, ai.store());
        }
    }

    @Test
    void perSpiSetterOverridesBackend() throws IOException {
        Store store = new InMemoryStore();
        JsonCodec override = new GsonJsonCodec();
        Backend backend = Backend.builder().store(store).build();
        try (AiJava ai = AiJava.builder().backend(backend).jsonCodec(override).build()) {
            assertSame(override, ai.jsonCodec());
            assertSame(store, ai.store());
        }
    }

    @Test
    void noStoreAnywhereThrows() {
        assertThrows(IllegalStateException.class, () -> AiJava.builder().build());
    }
}
