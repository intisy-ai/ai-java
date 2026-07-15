package io.github.intisy.ai.jvm.backend;

import io.github.intisy.ai.jvm.backend.clock.SystemClock;
import io.github.intisy.ai.jvm.backend.env.SystemEnv;
import io.github.intisy.ai.jvm.backend.http.UrlConnectionHttpClient;
import io.github.intisy.ai.jvm.backend.json.GsonJsonCodec;
import io.github.intisy.ai.jvm.backend.log.SimpleLoggerAdapter;
import io.github.intisy.ai.jvm.backend.random.SecureRandomAdapter;
import io.github.intisy.ai.jvm.backend.store.InMemoryStore;
import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.Store;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendTest {

    @Test
    void defaultsUseJvmImplsAndNullNotifier() {
        Store store = new InMemoryStore();
        Backend b = Backends.defaults(store);
        assertSame(store, b.store());
        assertTrue(b.httpClient() instanceof UrlConnectionHttpClient);
        assertTrue(b.jsonCodec() instanceof GsonJsonCodec);
        assertTrue(b.clock() instanceof SystemClock);
        assertTrue(b.random() instanceof SecureRandomAdapter);
        assertTrue(b.logger() instanceof SimpleLoggerAdapter);
        assertTrue(b.env() instanceof SystemEnv);
        assertNull(b.notifier(), "defaults leave notifier null so AiJava resolves the store-derived default");
    }

    @Test
    void builderOverridesLayerOverDefaults() {
        Store store = new InMemoryStore();
        JsonCodec custom = new GsonJsonCodec();
        Backend b = Backend.builder().store(store).jsonCodec(custom).build();
        assertSame(store, b.store());
        assertSame(custom, b.jsonCodec());
        // an unspecified SPI still falls back to the JVM default
        assertTrue(b.clock() instanceof SystemClock);
    }

    @Test
    void buildWithoutStoreThrows() {
        assertThrows(IllegalStateException.class, () -> Backend.builder().build());
    }
}
