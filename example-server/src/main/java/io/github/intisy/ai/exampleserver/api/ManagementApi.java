package io.github.intisy.ai.exampleserver.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.github.intisy.ai.exampleserver.AppProfiles;
import io.github.intisy.ai.exampleserver.admin.AccountAdmin;
import io.github.intisy.ai.exampleserver.admin.ConfigAdmin;
import io.github.intisy.ai.exampleserver.admin.OAuthAdmin;
import io.github.intisy.ai.exampleserver.admin.ProxyAdmin;
import io.github.intisy.ai.exampleserver.admin.QuotaAdmin;
import io.github.intisy.ai.exampleserver.admin.RoutingAdmin;
import io.github.intisy.ai.exampleserver.discovery.ProviderRegistryHolder;
import io.github.intisy.ai.exampleserver.discovery.ProviderSource;
import io.github.intisy.ai.shared.routing.RoutingProfile;
import io.github.intisy.ai.shared.spi.JsonCodec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Small hand-rolled JSON API over {@link AccountAdmin} + the discovered provider ids, registered
 * at the {@code /api} context. Routes by exact path + method only (no path templating library):
 *
 * <pre>
 * GET    /api/providers                                 -> [{"id":..,"accounts":&lt;int&gt;}]
 * GET    /api/providers/available                        -> [{"name":..,"assetName":..,"installed":bool}]
 * POST   /api/providers/install         {"name":..}       -> 200 {"installed":true,"providers":[...]}
 * GET    /api/providers/{id}/accounts                    -> [AccountView...]
 * POST   /api/providers/{id}/accounts    {refresh,...}    -> 200 AccountView (seed from a pasted token)
 * POST   /api/providers/{id}/accounts/{accId}/enable     -> 204
 * POST   /api/providers/{id}/accounts/{accId}/disable    -> 204
 * DELETE /api/providers/{id}/accounts/{accId}            -> 204
 * POST   /api/providers/{id}/models/discover              -> 200 {"provider":..,"models":[...]}
 * POST   /api/providers/{id}/quota/refresh                -> 200 {"accounts":[...]}
 * GET    /api/routing/catalog                            -> 200 <models.json>
 * GET    /api/routing/model-map          ?app=<app>       -> 200 {"tiers":[...],"map":{...}}
 * PUT    /api/routing/model-map          ?app=<app>       -> 200 {"ok":true,"warnings":[...]}
 *                                        {"map":{...}}
 * (app is optional; missing/blank -> the server's default routing profile, for back-compat)
 * GET    /api/providers/{id}/config                       -> 200 {"groups":[...],"values":{...}}
 * PUT    /api/providers/{id}/config      {"values":{...}} -> 200 {"values":{...}}
 * POST   /api/providers/{id}/oauth/authorize              -> 200 {"authorizeUrl":..,"completion":..}
 * POST   /api/providers/{id}/oauth/complete  {code,state} -> 200 {"account":..}
 * GET    /api/proxies                                    -> 200 [{app,profile,port,running,error}]
 * PUT    /api/proxies/{app}              {"port":N}       -> 200 {status}/400
 * POST   /api/proxies/{app}/start                         -> 200 {status}/400
 * POST   /api/proxies/{app}/stop                          -> 200 {status}/400
 * (anything else under /api/)                            -> 404 {"error":"not found"}
 * </pre>
 *
 * Path segments ({@code id}/{@code accId}) are URL-decoded exactly once. The whole handler body
 * is wrapped so an unexpected exception never leaks a stack trace to the client — it degrades to
 * a plain 500 JSON error instead.
 *
 * <p>{@code available}/{@code install} are reserved single-segment names under {@code /api/providers/},
 * checked as exact 3-segment paths before the (4+ segment) {@code {id}/accounts...} routes, so
 * there is no ambiguity between a provider id happening to be named "available" or "install" and
 * these reserved routes -- the existing routes never match at 3 segments in the first place.
 *
 * <p>The {@code /api/routing/*} + discover routes are only served once a {@link RoutingAdmin} is
 * wired in (the 7-arg constructor); callers still on an older constructor get a plain 404 for
 * these paths instead of an NPE. Same story for {@code /quota/refresh} and {@link QuotaAdmin}
 * (the 8-arg constructor), and for {@code /api/providers/{id}/config} and {@link ConfigAdmin}
 * (the 9-arg constructor). Same for the {@code /oauth/*} routes and {@link OAuthAdmin} (the
 * 10-arg constructor), and for the {@code /api/proxies*} routes and {@link ProxyAdmin} (the
 * 11-arg constructor).
 */
public final class ManagementApi implements HttpHandler {

    private final Supplier<List<String>> providerIds;
    private final AccountAdmin admin;
    private final JsonCodec json;
    private final ProviderSource source;
    private final Path providersDir;
    private final ProviderRegistryHolder holder;
    private final RoutingAdmin routing;
    private final QuotaAdmin quota;
    private final ConfigAdmin config;
    private final OAuthAdmin oauth;
    private final ProxyAdmin proxy;

    public ManagementApi(Supplier<List<String>> providerIds, AccountAdmin admin, JsonCodec json) {
        this(providerIds, admin, json, null, null, null, null, null);
    }

    /**
     * Adds the on-demand install surface ({@code GET /api/providers/available} and
     * {@code POST /api/providers/install}) on top of the base account-admin routes.
     * {@code source} lists/downloads installable provider jars, {@code providersDir} is where they
     * land on disk, and {@code holder} is refreshed after a successful download so the newly
     * installed provider becomes routable without a restart. No {@link RoutingAdmin}/{@link
     * QuotaAdmin} — the {@code /api/routing/*}, discover, and quota/refresh routes 404 (see the
     * full 8-arg constructor for the full wiring).
     */
    public ManagementApi(Supplier<List<String>> providerIds, AccountAdmin admin, JsonCodec json,
                          ProviderSource source, Path providersDir, ProviderRegistryHolder holder) {
        this(providerIds, admin, json, source, providersDir, holder, null, null);
    }

    /**
     * Adds the routing surface ({@code POST .../models/discover}, {@code GET/PUT
     * /api/routing/*}) backed by {@code routing}. No {@link QuotaAdmin} — {@code quota/refresh}
     * 404s (see the full 8-arg constructor for the full wiring).
     */
    public ManagementApi(Supplier<List<String>> providerIds, AccountAdmin admin, JsonCodec json,
                          ProviderSource source, Path providersDir, ProviderRegistryHolder holder,
                          RoutingAdmin routing) {
        this(providerIds, admin, json, source, providersDir, holder, routing, null);
    }

    /**
     * Adds the quota surface ({@code POST .../quota/refresh}) backed by {@code quota}. No
     * {@link ConfigAdmin} — {@code /api/providers/{id}/config} 404s (see the full 9-arg
     * constructor for the full wiring).
     */
    public ManagementApi(Supplier<List<String>> providerIds, AccountAdmin admin, JsonCodec json,
                          ProviderSource source, Path providersDir, ProviderRegistryHolder holder,
                          RoutingAdmin routing, QuotaAdmin quota) {
        this(providerIds, admin, json, source, providersDir, holder, routing, quota, null);
    }

    /**
     * Adds the provider-config surface ({@code GET/PUT /api/providers/{id}/config}) backed by
     * {@code config}. No {@link OAuthAdmin} — the {@code /oauth/*} routes 404 (see the full 10-arg
     * constructor for the full wiring).
     */
    public ManagementApi(Supplier<List<String>> providerIds, AccountAdmin admin, JsonCodec json,
                          ProviderSource source, Path providersDir, ProviderRegistryHolder holder,
                          RoutingAdmin routing, QuotaAdmin quota, ConfigAdmin config) {
        this(providerIds, admin, json, source, providersDir, holder, routing, quota, config, null);
    }

    /**
     * Adds the OAuth-login surface ({@code POST .../oauth/authorize}, {@code POST
     * .../oauth/complete}) backed by {@code oauth}. No {@link ProxyAdmin} — the
     * {@code /api/proxies*} routes 404 (see the full 11-arg constructor for the full wiring).
     */
    public ManagementApi(Supplier<List<String>> providerIds, AccountAdmin admin, JsonCodec json,
                          ProviderSource source, Path providersDir, ProviderRegistryHolder holder,
                          RoutingAdmin routing, QuotaAdmin quota, ConfigAdmin config, OAuthAdmin oauth) {
        this(providerIds, admin, json, source, providersDir, holder, routing, quota, config, oauth, null);
    }

    /**
     * Full constructor: adds the proxy-management surface ({@code /api/proxies*}) backed by
     * {@code proxy}.
     */
    public ManagementApi(Supplier<List<String>> providerIds, AccountAdmin admin, JsonCodec json,
                          ProviderSource source, Path providersDir, ProviderRegistryHolder holder,
                          RoutingAdmin routing, QuotaAdmin quota, ConfigAdmin config, OAuthAdmin oauth,
                          ProxyAdmin proxy) {
        this.providerIds = providerIds;
        this.admin = admin;
        this.json = json;
        this.source = source;
        this.providersDir = providersDir;
        this.holder = holder;
        this.routing = routing;
        this.quota = quota;
        this.config = config;
        this.oauth = oauth;
        this.proxy = proxy;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            route(exchange);
        } catch (RuntimeException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "internal server error");
            respondJson(exchange, 500, body);
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String[] seg = segments(exchange.getRequestURI().getPath());

        if ("GET".equals(method) && seg.length == 2
                && "api".equals(seg[0]) && "providers".equals(seg[1])) {
            handleListProviders(exchange);
            return;
        }
        if ("GET".equals(method) && seg.length == 3
                && "api".equals(seg[0]) && "providers".equals(seg[1]) && "available".equals(seg[2])) {
            handleAvailable(exchange);
            return;
        }
        if ("POST".equals(method) && seg.length == 3
                && "api".equals(seg[0]) && "providers".equals(seg[1]) && "install".equals(seg[2])) {
            handleInstall(exchange);
            return;
        }
        if ("GET".equals(method) && seg.length == 4
                && "api".equals(seg[0]) && "providers".equals(seg[1]) && "accounts".equals(seg[3])) {
            handleListAccounts(exchange, decode(seg[2]));
            return;
        }
        if ("POST".equals(method) && seg.length == 4
                && "api".equals(seg[0]) && "providers".equals(seg[1]) && "accounts".equals(seg[3])) {
            handleAddAccount(exchange, decode(seg[2]));
            return;
        }
        if ("POST".equals(method) && seg.length == 6
                && "api".equals(seg[0]) && "providers".equals(seg[1]) && "accounts".equals(seg[3])
                && ("enable".equals(seg[5]) || "disable".equals(seg[5]))) {
            handleSetEnabled(exchange, decode(seg[2]), decode(seg[4]), "enable".equals(seg[5]));
            return;
        }
        if ("DELETE".equals(method) && seg.length == 5
                && "api".equals(seg[0]) && "providers".equals(seg[1]) && "accounts".equals(seg[3])) {
            handleRemove(exchange, decode(seg[2]), decode(seg[4]));
            return;
        }
        if ("POST".equals(method) && seg.length == 5
                && "api".equals(seg[0]) && "providers".equals(seg[1])
                && "models".equals(seg[3]) && "discover".equals(seg[4])) {
            handleDiscover(exchange, decode(seg[2]));
            return;
        }
        if ("GET".equals(method) && seg.length == 3
                && "api".equals(seg[0]) && "routing".equals(seg[1]) && "catalog".equals(seg[2])) {
            handleCatalog(exchange);
            return;
        }
        if ("GET".equals(method) && seg.length == 3
                && "api".equals(seg[0]) && "routing".equals(seg[1]) && "model-map".equals(seg[2])) {
            handleModelMapGet(exchange);
            return;
        }
        if ("PUT".equals(method) && seg.length == 3
                && "api".equals(seg[0]) && "routing".equals(seg[1]) && "model-map".equals(seg[2])) {
            handleModelMapPut(exchange);
            return;
        }
        if ("POST".equals(method) && seg.length == 5
                && "api".equals(seg[0]) && "providers".equals(seg[1])
                && "quota".equals(seg[3]) && "refresh".equals(seg[4])) {
            handleQuotaRefresh(exchange, decode(seg[2]));
            return;
        }
        if ("GET".equals(method) && seg.length == 4
                && "api".equals(seg[0]) && "providers".equals(seg[1]) && "config".equals(seg[3])) {
            handleGetConfig(exchange, decode(seg[2]));
            return;
        }
        if ("PUT".equals(method) && seg.length == 4
                && "api".equals(seg[0]) && "providers".equals(seg[1]) && "config".equals(seg[3])) {
            handlePutConfig(exchange, decode(seg[2]));
            return;
        }
        if ("POST".equals(method) && seg.length == 5
                && "api".equals(seg[0]) && "providers".equals(seg[1])
                && "oauth".equals(seg[3]) && "authorize".equals(seg[4])) {
            handleOAuthAuthorize(exchange, decode(seg[2]));
            return;
        }
        if ("POST".equals(method) && seg.length == 5
                && "api".equals(seg[0]) && "providers".equals(seg[1])
                && "oauth".equals(seg[3]) && "complete".equals(seg[4])) {
            handleOAuthComplete(exchange, decode(seg[2]));
            return;
        }
        if ("GET".equals(method) && seg.length == 2
                && "api".equals(seg[0]) && "proxies".equals(seg[1])) {
            handleProxiesList(exchange);
            return;
        }
        if ("PUT".equals(method) && seg.length == 3
                && "api".equals(seg[0]) && "proxies".equals(seg[1])) {
            handleProxyPut(exchange, decode(seg[2]));
            return;
        }
        if ("POST".equals(method) && seg.length == 4
                && "api".equals(seg[0]) && "proxies".equals(seg[1])
                && ("start".equals(seg[3]) || "stop".equals(seg[3]))) {
            handleProxyLifecycle(exchange, decode(seg[2]), "start".equals(seg[3]));
            return;
        }
        handleNotFound(exchange);
    }

    private void handleListProviders(HttpExchange exchange) throws IOException {
        List<String> ids = providerIds.get();
        List<Map<String, Object>> body = new ArrayList<>();
        if (ids != null) {
            for (String id : ids) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", id);
                entry.put("accounts", admin.list(id).size());
                body.add(entry);
            }
        }
        respondJson(exchange, 200, body);
    }

    private void handleAvailable(HttpExchange exchange) throws IOException {
        List<Map<String, Object>> body = new ArrayList<>();
        // An org entry whose name matches an already-installed provider id counts as installed
        // even when no jar sits under its (possibly renamed) assetName -- avoids a duplicate
        // "not installed" listing for a provider that was renamed to match its org asset.
        Set<String> installedIds = new HashSet<>(holder.listProviderIds());
        for (ProviderSource.Entry entry : source.list()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", entry.name);
            item.put("assetName", entry.assetName);
            boolean installed = Files.exists(providersDir.resolve(entry.assetName))
                    || installedIds.contains(entry.name);
            item.put("installed", installed);
            body.add(item);
        }
        respondJson(exchange, 200, body);
    }

    private void handleInstall(HttpExchange exchange) throws IOException {
        Object parsed = json.parse(readBody(exchange.getRequestBody()));
        String name = null;
        if (parsed instanceof Map) {
            Object nameObj = ((Map<?, ?>) parsed).get("name");
            if (nameObj instanceof String) {
                name = (String) nameObj;
            }
        }

        ProviderSource.Entry match = null;
        for (ProviderSource.Entry entry : source.list()) {
            if (entry.name.equals(name)) {
                match = entry;
                break;
            }
        }
        if (match == null) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "unknown provider");
            respondJson(exchange, 404, body);
            return;
        }

        try {
            source.download(match, providersDir);
        } catch (IOException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "download failed: " + e.getMessage());
            respondJson(exchange, 502, body);
            return;
        }
        holder.refresh(providersDir);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("installed", true);
        body.put("providers", holder.listProviderIds());
        respondJson(exchange, 200, body);
    }

    private void handleListAccounts(HttpExchange exchange, String providerId) throws IOException {
        respondJson(exchange, 200, admin.list(providerId));
    }

    /**
     * Seeds an account from a pasted OAuth refresh token (the JVM login MVP). Body:
     * {@code {"refresh":..,"email":..,"id":..,"projectId":..,"managedProjectId":..}} — only
     * {@code refresh} plus one of {@code email}/{@code id} are required. This only becomes
     * visible to an installed provider when the server runs against a {@code FileStore} shared
     * with that provider ({@code -Dexampleserver.store=file -Dexampleserver.configDir=<dir>});
     * under the default in-memory store it is admin-visible only.
     */
    private void handleAddAccount(HttpExchange exchange, String providerId) throws IOException {
        Map<?, ?> body = asMap(json.parse(readBody(exchange.getRequestBody())));
        String refresh = stringField(body, "refresh");
        String email = stringField(body, "email");
        String id = stringField(body, "id");
        String projectId = stringField(body, "projectId");
        String managedProjectId = stringField(body, "managedProjectId");

        try {
            AccountAdmin.AccountView view = admin.addToken(providerId, id, email, refresh, projectId, managedProjectId);
            respondJson(exchange, 200, view);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            respondJson(exchange, 400, error);
        }
    }

    private static Map<?, ?> asMap(Object parsed) {
        return parsed instanceof Map ? (Map<?, ?>) parsed : null;
    }

    private static String stringField(Map<?, ?> body, String key) {
        if (body == null) return null;
        Object value = body.get(key);
        return value instanceof String ? (String) value : null;
    }

    private void handleSetEnabled(HttpExchange exchange, String providerId, String accountId, boolean enabled)
            throws IOException {
        admin.setEnabled(providerId, accountId, enabled);
        respondNoBody(exchange, 204);
    }

    private void handleRemove(HttpExchange exchange, String providerId, String accountId) throws IOException {
        admin.remove(providerId, accountId);
        respondNoBody(exchange, 204);
    }

    private void handleDiscover(HttpExchange exchange, String providerId) throws IOException {
        if (routing == null) {
            handleNotFound(exchange);
            return;
        }
        try {
            respondJson(exchange, 200, routing.discover(providerId));
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            respondJson(exchange, 400, error);
        }
    }

    private void handleCatalog(HttpExchange exchange) throws IOException {
        if (routing == null) {
            handleNotFound(exchange);
            return;
        }
        respondJson(exchange, 200, json.parse(routing.catalogJson()));
    }

    private void handleModelMapGet(HttpExchange exchange) throws IOException {
        if (routing == null) {
            handleNotFound(exchange);
            return;
        }
        try {
            RoutingProfile p = profileFromQuery(exchange);
            respondJson(exchange, 200, p == null ? routing.modelMapView() : routing.modelMapView(p));
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            respondJson(exchange, 400, error);
        }
    }

    private void handleModelMapPut(HttpExchange exchange) throws IOException {
        if (routing == null) {
            handleNotFound(exchange);
            return;
        }
        try {
            RoutingProfile p = profileFromQuery(exchange);
            Map<?, ?> body = asMap(json.parse(readBody(exchange.getRequestBody())));
            Object mapObj = body != null ? body.get("map") : null;
            if (!(mapObj instanceof Map)) {
                throw new IllegalArgumentException("body.map must be an object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) mapObj;
            respondJson(exchange, 200, p == null ? routing.putModelMap(map) : routing.putModelMap(p, map));
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            respondJson(exchange, 400, error);
        }
    }

    // A missing/blank ?app= keeps existing behavior (the server's default routing profile);
    // an unrecognized app propagates AppProfiles.byApp's IllegalArgumentException to the 400 path.
    private RoutingProfile profileFromQuery(HttpExchange exchange) {
        String app = queryParam(exchange, "app");
        return (app == null || app.isEmpty()) ? null : AppProfiles.byApp(app);
    }

    private static String queryParam(HttpExchange exchange, String key) {
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) return null;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            if (key.equals(decode(k))) return eq >= 0 ? decode(pair.substring(eq + 1)) : "";
        }
        return null;
    }

    private void handleQuotaRefresh(HttpExchange exchange, String providerId) throws IOException {
        if (quota == null) {
            handleNotFound(exchange);
            return;
        }
        try {
            respondJson(exchange, 200, quota.combined(providerId));
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            respondJson(exchange, 400, error);
        }
    }

    private void handleGetConfig(HttpExchange exchange, String providerId) throws IOException {
        if (config == null) {
            handleNotFound(exchange);
            return;
        }
        try {
            Map<String, Object> result = config.getConfig(providerId);
            if (result == null) {
                handleNotFound(exchange);
                return;
            }
            respondJson(exchange, 200, result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            respondJson(exchange, 400, error);
        }
    }

    private void handlePutConfig(HttpExchange exchange, String providerId) throws IOException {
        if (config == null) {
            handleNotFound(exchange);
            return;
        }
        try {
            Map<?, ?> body = asMap(json.parse(readBody(exchange.getRequestBody())));
            Object valuesObj = body != null ? body.get("values") : null;
            if (!(valuesObj instanceof Map)) {
                throw new IllegalArgumentException("body.values must be an object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> values = (Map<String, Object>) valuesObj;
            respondJson(exchange, 200, config.putConfig(providerId, values));
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            respondJson(exchange, 400, error);
        }
    }

    private void handleOAuthAuthorize(HttpExchange exchange, String providerId) throws IOException {
        if (oauth == null) {
            handleNotFound(exchange);
            return;
        }
        try {
            respondJson(exchange, 200, oauth.authorize(providerId));
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            respondJson(exchange, 400, error);
        }
    }

    private void handleOAuthComplete(HttpExchange exchange, String providerId) throws IOException {
        if (oauth == null) {
            handleNotFound(exchange);
            return;
        }
        try {
            Map<?, ?> body = asMap(json.parse(readBody(exchange.getRequestBody())));
            String code = stringField(body, "code");
            String state = stringField(body, "state");
            respondJson(exchange, 200, oauth.complete(providerId, code, state));
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            respondJson(exchange, 400, error);
        }
    }

    private void handleProxiesList(HttpExchange exchange) throws IOException {
        if (proxy == null) {
            handleNotFound(exchange);
            return;
        }
        respondJson(exchange, 200, proxy.list());
    }

    private void handleProxyPut(HttpExchange exchange, String app) throws IOException {
        if (proxy == null) {
            handleNotFound(exchange);
            return;
        }
        try {
            Map<?, ?> body = asMap(json.parse(readBody(exchange.getRequestBody())));
            Object portObj = body != null ? body.get("port") : null;
            if (!(portObj instanceof Number)) {
                throw new IllegalArgumentException("body.port must be a number");
            }
            respondJson(exchange, 200, proxy.setPort(app, ((Number) portObj).intValue()));
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            respondJson(exchange, 400, error);
        }
    }

    private void handleProxyLifecycle(HttpExchange exchange, String app, boolean start) throws IOException {
        if (proxy == null) {
            handleNotFound(exchange);
            return;
        }
        try {
            respondJson(exchange, 200, start ? proxy.start(app) : proxy.stop(app));
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            respondJson(exchange, 400, error);
        }
    }

    private void handleNotFound(HttpExchange exchange) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "not found");
        respondJson(exchange, 404, body);
    }

    /** Path split into non-empty segments, e.g. {@code /api/providers} -> {@code ["api","providers"]}. */
    private static String[] segments(String path) {
        String[] raw = path.split("/", -1);
        if (raw.length > 0 && raw[0].isEmpty()) {
            return Arrays.copyOfRange(raw, 1, raw.length);
        }
        return raw;
    }

    private static String readBody(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toString("UTF-8");
    }

    private static String decode(String segment) {
        try {
            return URLDecoder.decode(segment, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return segment; // UTF-8 is always supported; unreachable in practice
        }
    }

    private void respondJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = json.stringify(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void respondNoBody(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }
}
