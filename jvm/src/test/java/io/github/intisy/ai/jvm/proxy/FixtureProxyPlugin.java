package io.github.intisy.ai.jvm.proxy;

import io.github.intisy.ai.shared.routing.ProxyPlugin;
import io.github.intisy.ai.shared.routing.RoutingProfile;

/** Test-only ProxyPlugin used to prove ServiceLoader discovery from the test classpath. */
public final class FixtureProxyPlugin implements ProxyPlugin {
    @Override public String id() { return "fixture"; }
    @Override public String displayName() { return "Fixture Proxy"; }
    @Override public RoutingProfile profile() { return null; } // passthrough, no tiers
}
