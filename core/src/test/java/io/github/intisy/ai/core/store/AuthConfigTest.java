package io.github.intisy.ai.core.store;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthConfigTest {
    @Test
    void setActiveProvider_thenActiveProvider_roundTripsAndWritesPreferredPath() throws Exception {
        Path dir = Files.createTempDirectory("ai-auth-config");
        Path configFolder = dir.resolve("config"); // matches ConfigDir.configFolder() convention

        AuthConfig cfg = new AuthConfig(configFolder);
        assertEquals("", cfg.activeProvider()); // JS parity: readConfig().provider || ""

        cfg.setActiveProvider("x");

        assertEquals("x", cfg.activeProvider());
        assertTrue(Files.exists(configFolder.resolve("auth.json")));
        assertFalse(Files.exists(dir.resolve("auth.json"))); // never writes the fallback path

        String json = Files.readString(configFolder.resolve("auth.json"));
        assertTrue(json.contains("\"provider\": \"x\""));
    }

    @Test
    void activeProvider_readsFallbackTopLevelAuthJsonWhenPreferredMissing() throws Exception {
        Path dir = Files.createTempDirectory("ai-auth-config-fallback");
        Path configFolder = dir.resolve("config");
        Files.write(dir.resolve("auth.json"), "{ \"provider\": \"fallback-provider\" }".getBytes());

        AuthConfig cfg = new AuthConfig(configFolder);
        assertEquals("fallback-provider", cfg.activeProvider());
    }

    @Test
    void activeProvider_readsLegacyCoreAuthJsonWhenNewerFilesMissing() throws Exception {
        Path dir = Files.createTempDirectory("ai-auth-config-legacy");
        Path configFolder = dir.resolve("config");
        Files.createDirectories(configFolder);
        Files.write(configFolder.resolve("core-auth.json"), "{ \"provider\": \"legacy-provider\" }".getBytes());

        AuthConfig cfg = new AuthConfig(configFolder);
        assertEquals("legacy-provider", cfg.activeProvider());
    }

    @Test
    void preferredPathWinsOverFallbackAndLegacy() throws Exception {
        Path dir = Files.createTempDirectory("ai-auth-config-precedence");
        Path configFolder = dir.resolve("config");
        Files.createDirectories(configFolder);
        Files.write(dir.resolve("auth.json"), "{ \"provider\": \"fallback\" }".getBytes());
        Files.write(configFolder.resolve("auth.json"), "{ \"provider\": \"preferred\" }".getBytes());

        AuthConfig cfg = new AuthConfig(configFolder);
        assertEquals("preferred", cfg.activeProvider());
    }
}
