package io.github.intisy.ai.exampleserver.admin;

import io.github.intisy.ai.exampleserver.ProxyManager;

import java.util.List;

/**
 * UI-safe facade over {@link ProxyManager} (the admin-class convention), so {@code ManagementApi}
 * never touches the manager's internals directly. Unknown-id is surfaced as
 * {@link IllegalArgumentException} for the API's 400 path.
 */
public final class ProxyAdmin {
    private final ProxyManager manager;

    /**
     * @param manager the proxy processes this admin starts, stops and reports on
     */
    public ProxyAdmin(ProxyManager manager) {
        this.manager = manager;
    }

    /** {@return the status of every installed proxy} */
    public List<ProxyManager.ProxyStatus> list() {
        return manager.list();
    }

    /**
     * {@return that proxy's status after the change}
     *
     * @param id the proxy to change
     * @param port the port it should listen on
     */
    public ProxyManager.ProxyStatus setPort(String id, int port) {
        return manager.setPort(id, port);
    }

    /**
     * {@return that proxy's status after the start attempt}
     *
     * @param id the proxy to start
     */
    public ProxyManager.ProxyStatus start(String id) {
        return manager.start(id);
    }

    /**
     * {@return that proxy's status after the stop attempt}
     *
     * @param id the proxy to stop
     */
    public ProxyManager.ProxyStatus stop(String id) {
        return manager.stop(id);
    }
}
