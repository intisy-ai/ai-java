package io.github.intisy.ai.shared.store;

import io.github.intisy.ai.shared.spi.Store;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthConfigTest {

    @Test
    void activeProvider_defaultsToEmptyStringWhenUnset() {
        AuthConfig cfg = new AuthConfig(new InMemoryStore(), new TestJsonCodec());
        assertEquals("", cfg.activeProvider());
    }

    @Test
    void setActiveProvider_thenActiveProvider_roundTrips() {
        Store store = new InMemoryStore();
        AuthConfig cfg = new AuthConfig(store, new TestJsonCodec());

        cfg.setActiveProvider("x");

        assertEquals("x", cfg.activeProvider());
        String raw = store.get("auth.json");
        assertTrue(raw.contains("\"provider\":\"x\""));
    }

    @Test
    void setActiveProvider_preservesOtherFieldsAlreadyInTheDocument() {
        Store store = new InMemoryStore();
        store.put("auth.json", "{\"other\":\"kept\"}");
        AuthConfig cfg = new AuthConfig(store, new TestJsonCodec());

        cfg.setActiveProvider("y");

        assertEquals("y", cfg.activeProvider());
        assertTrue(store.get("auth.json").contains("\"other\":\"kept\""));
    }

    /**
     * Best-effort read resilience (JS/core parity): a corrupted {@code auth.json} must degrade
     * to the default empty config rather than throwing out of {@code activeProvider}.
     */
    @Test
    void activeProvider_defaultsToEmptyStringWhenStoreContainsMalformedJson() {
        Store store = new InMemoryStore();
        store.put("auth.json", "{ not json");
        AuthConfig cfg = new AuthConfig(store, new TestJsonCodec());

        assertEquals("", cfg.activeProvider());
    }
}
