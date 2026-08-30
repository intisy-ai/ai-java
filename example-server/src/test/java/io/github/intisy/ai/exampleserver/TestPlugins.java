package io.github.intisy.ai.exampleserver;

import io.github.intisy.ai.auth.contracts.AuthContracts;
import io.github.intisy.ai.ir.IrContracts;
import io.github.intisy.ai.jvm.plugin.Plugins;
import io.github.intisy.ai.shared.routing.ProxyContracts;

import java.util.Arrays;

/**
 * A plugin host for one test, carrying the same capability vocabulary the server declares.
 *
 * @implNote One per test rather than one shared: a host holds registrations, so two tests sharing
 * one would see each other's plugins and the order they ran in would start to matter.
 */
final class TestPlugins {

    /**
     * A fresh host.
     *
     * @return it, recognising the three capabilities this server resolves
     */
    static Plugins create() {
        return new Plugins("ai-java-test", Arrays.asList(
                AuthContracts.PROVIDER_ID, ProxyContracts.APP_PROXY_ID, IrContracts.TRANSLATOR_ID));
    }

    private TestPlugins() {
    }
}
