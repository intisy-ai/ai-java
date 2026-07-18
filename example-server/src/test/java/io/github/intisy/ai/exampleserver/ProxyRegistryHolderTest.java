package io.github.intisy.ai.exampleserver;

import io.github.intisy.ai.exampleserver.discovery.ProxyDiscovery;
import io.github.intisy.ai.exampleserver.discovery.ProxyRegistryHolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyRegistryHolderTest {
    @Test
    void emptyDirYieldsEmptyHolder(@TempDir Path dir) {
        ProxyRegistryHolder holder = new ProxyRegistryHolder(ProxyDiscovery.resolve(dir));
        assertTrue(holder.listProxyIds().isEmpty());
        assertNull(holder.profileFor("claude-code"));
        assertNull(holder.displayNameFor("claude-code"));
        assertFalse(holder.uninstall("claude-code", dir));
    }
}
