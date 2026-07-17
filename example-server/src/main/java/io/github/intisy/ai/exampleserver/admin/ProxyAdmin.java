package io.github.intisy.ai.exampleserver.admin;

import io.github.intisy.ai.exampleserver.ProxyManager;

import java.util.List;

/**
 * UI-safe facade over {@link ProxyManager} (the admin-class convention), so {@code ManagementApi}
 * never touches the manager's internals directly. Unknown-app is surfaced as
 * {@link IllegalArgumentException} for the API's 400 path.
 */
public final class ProxyAdmin {
    private final ProxyManager manager;

    public ProxyAdmin(ProxyManager manager) {
        this.manager = manager;
    }

    public List<ProxyManager.ProxyStatus> list() {
        return manager.list();
    }

    public ProxyManager.ProxyStatus setPort(String app, int port) {
        return manager.setPort(app, port);
    }

    public ProxyManager.ProxyStatus start(String app) {
        return manager.start(app);
    }

    public ProxyManager.ProxyStatus stop(String app) {
        return manager.stop(app);
    }
}
