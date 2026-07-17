package io.github.intisy.ai.jvm.proxy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ProxyRegistryTest {
    @Test
    public void emptyRegistryHasNoProxies() {
        ProxyRegistry reg = ProxyRegistry.empty();
        assertTrue(reg.listProxyIds().isEmpty());
        assertNull(reg.pluginFor("fixture"));
        assertNull(reg.profileFor("fixture"));
    }

    @Test
    public void missingDirectoryYieldsEmptyRegistry() {
        ProxyRegistry reg = ProxyRegistry.fromDirectory(java.nio.file.Paths.get("does-not-exist-xyz"));
        assertTrue(reg.listProxyIds().isEmpty());
    }
}
