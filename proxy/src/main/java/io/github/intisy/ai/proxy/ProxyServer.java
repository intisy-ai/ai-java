package io.github.intisy.ai.proxy;

import java.io.IOException;

/**
 * The always-on inbound proxy daemon. Java port of {@code libs/core-proxy/src/server.ts}'s
 * {@code createProxyServer} over the JDK {@link com.sun.net.httpserver.HttpServer}: routes
 * each request to the {@code {provider, model}} chain assigned to its tier, falling back
 * through the chain on rate-limit and synthesizing a native 429 once every entry in the
 * chain is exhausted.
 */
public interface ProxyServer {

    /**
     * Binds {@code 127.0.0.1:port} and starts serving (port {@code 0} picks an ephemeral
     * port).
     *
     * @return the actual bound port.
     */
    int listen() throws IOException;

    /** Stops the server immediately, releasing its listening socket and executor. */
    void close();

    static ProxyServer createProxyServer(ProxyOptions opts) {
        return new ProxyServerImpl(opts);
    }
}
