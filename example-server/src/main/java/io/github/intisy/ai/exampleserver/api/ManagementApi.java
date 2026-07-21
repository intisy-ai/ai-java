package io.github.intisy.ai.exampleserver.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.github.intisy.ai.exampleserver.admin.AccountAdmin;
import io.github.intisy.ai.exampleserver.admin.ConfigAdmin;
import io.github.intisy.ai.exampleserver.admin.MessagesAdmin;
import io.github.intisy.ai.exampleserver.admin.OAuthAdmin;
import io.github.intisy.ai.exampleserver.admin.ProxyAdmin;
import io.github.intisy.ai.exampleserver.admin.QuotaAdmin;
import io.github.intisy.ai.exampleserver.admin.RoutingAdmin;
import io.github.intisy.ai.exampleserver.discovery.GithubAuth;
import io.github.intisy.ai.exampleserver.discovery.GithubOrgScan;
import io.github.intisy.ai.exampleserver.discovery.InstalledVersions;
import io.github.intisy.ai.exampleserver.discovery.ProviderRegistryHolder;
import io.github.intisy.ai.exampleserver.discovery.ProviderSource;
import io.github.intisy.ai.exampleserver.discovery.ProxyRegistryHolder;
import io.github.intisy.ai.exampleserver.discovery.ProxySource;
import io.github.intisy.ai.shared.routing.RoutingProfile;
import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.http.HttpResponse;

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
 * GET    /api/providers/available                        -> [{"name":..,"assetName":..,"installed":bool,
 *                                                             "version":..,"installedVersion":..,"updateAvailable":bool}]
 * POST   /api/providers/install         {"name":..}       -> 200 {"installed":true,"providers":[...]}
 * DELETE /api/providers/{id}                             -> 200 {"uninstalled":true,"providers":[...]} / 404
 * POST   /api/providers/{id}/update                       -> 200 {"updated":true,"version":..,"providers":[...]} / 404
 * GET    /api/providers/{id}/accounts                    -> [AccountView...]
 * POST   /api/providers/{id}/accounts    {refresh,...}    -> 200 AccountView (seed from a pasted token)
 * POST   /api/providers/{id}/accounts/{accId}/enable     -> 204
 * POST   /api/providers/{id}/accounts/{accId}/disable    -> 204
 * DELETE /api/providers/{id}/accounts/{accId}            -> 204
 * POST   /api/providers/{id}/models/discover              -> 200 {"provider":..,"models":[...]}
 * POST   /api/providers/{id}/quota/refresh                -> 200 {"accounts":[...]}
 * POST   /api/providers/{id}/messages    {model,...}       -> &lt;provider's HttpResponse, verbatim&gt;
 * GET    /api/routing/catalog                            -> 200 <models.json>
 * GET    /api/routing/model-map          ?app=<app>       -> 200 {"tiers":[...],"map":{...}}
 * PUT    /api/routing/model-map          ?app=<app>       -> 200 {"ok":true,"warnings":[...]}
 *                                        {"map":{...}}
 * (app is REQUIRED -- routing is per-installed-proxy only, there is no default/built-in profile;
 * a missing/blank/unknown ?app= 400s with "select a proxy for routing")
 * GET    /api/providers/{id}/config                       -> 200 {"groups":[...],"values":{...}}
 * PUT    /api/providers/{id}/config      {"values":{...}} -> 200 {"values":{...}}
 * POST   /api/providers/{id}/oauth/authorize              -> 200 {"authorizeUrl":..,"completion":..}
 * POST   /api/providers/{id}/oauth/complete  {code,state} -> 200 {"account":..}
 * GET    /api/proxies                                    -> 200 [{id,displayName,port,running,routing,error}]
 * GET    /api/proxies/available                          -> [{"name":..,"assetName":..,"installed":bool}]
 * POST   /api/proxies/install           {"name":..}       -> 200 {"installed":true,"proxies":[...]}
 * DELETE /api/proxies/{id}                               -> 200 {"uninstalled":true,"proxies":[...]} / 404
 * PUT    /api/proxies/{id}               {"port":N}       -> 200 {status}/400
 * POST   /api/proxies/{id}/start                          -> 200 {status}/400
 * POST   /api/proxies/{id}/stop                           -> 200 {status}/400
 * GET    /api/github                                     -> 200 {connected,source,login,rateLimitRemaining}
 * POST   /api/github/detect                               -> 200 {...status,"detected":bool}
 * POST   /api/github/token       {"token":".."}           -> 200 {...status} (never echoes the token)
 * DELETE /api/github                                     -> 200 {...status} (clears the manual token only)
 * (anything else under /api/)                            -> 404 {"error":"not found"}
 * </pre>
 *
 * Path segments ({@code id}/{@code accId}) are URL-decoded exactly once. The whole handler body
 * is wrapped so an unexpected exception never leaks a stack trace to the client: it degrades to
 * a plain 500 JSON error instead.
 *
 * <p>{@code available}/{@code install} are reserved single-segment names under {@code /api/providers/}
 * and {@code /api/proxies/}, checked as exact 3-segment paths before the (4+ segment)
 * {@code {id}/accounts...} routes (providers) or the {@code {app}/start|stop} routes (proxies), so
 * there is no ambiguity between an id happening to be named "available" or "install" and
 * these reserved routes -- the existing routes never match at 3 segments in the first place
 * (the proxy {@code PUT /api/proxies/{app}} route is also 3-segment but a different HTTP method).
 *
 * <p>The {@code /api/routing/*} + discover routes are only served once a {@link RoutingAdmin} is
 * wired in (the 7-arg constructor); callers still on an older constructor get a plain 404 for
 * these paths instead of an NPE. Same story for {@code /quota/refresh} and {@link QuotaAdmin}
 * (the 8-arg constructor), and for {@code /api/providers/{id}/config} and {@link ConfigAdmin}
 * (the 9-arg constructor). Same for the {@code /oauth/*} routes and {@link OAuthAdmin} (the
 * 10-arg constructor), and for the {@code /api/proxies*} routes and {@link ProxyAdmin} (the
 * 11-arg constructor). The proxy install/available/uninstall routes, and per-app resolution of
 * {@code ?app=} in {@code /api/routing/model-map} against an INSTALLED PROXY (rather than the
 * previously hardcoded per-app table), additionally require {@link ProxySource}/{@link
 * ProxyRegistryHolder} (the full constructor); without them those three routes 404 and an
 * {@code ?app=} query 400s with "unknown proxy: ...". {@code POST /api/providers/{id}/messages}
 * (console chat = a DIRECT provider call, no router) additionally requires a {@link MessagesAdmin}
 * (the 15-arg constructor); without one that route 404s.
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
    private final ProxySource proxySource;
    private final ProxyRegistryHolder proxyHolder;
    private final Path proxiesDir;
    private final MessagesAdmin messages;
    private final GithubAuth githubAuth;
    private final GithubOrgScan githubScan;

    /**
     * Adds the on-demand install surface ({@code GET /api/providers/available} and
     * {@code POST /api/providers/install}) on top of the base account-admin routes.
     * {@code source} lists/downloads installable provider jars, {@code providersDir} is where they
     * land on disk, and {@code holder} is refreshed after a successful download so the newly
     * installed provider becomes routable without a restart. No {@link RoutingAdmin}/{@link
     * QuotaAdmin}: the {@code /api/routing/*}, discover, and quota/refresh routes 404 (see the
     * full 8-arg constructor for the full wiring).
     */
    public ManagementApi(Supplier<List<String>> providerIds, AccountAdmin admin, JsonCodec json,
                          ProviderSource source, Path providersDir, ProviderRegistryHolder holder) {
        this(providerIds, admin, json, source, providersDir, holder, null, null);
    }

    /**
     * Adds the quota surface ({@code POST .../quota/refresh}) backed by {@code quota}. No
     * {@link ConfigAdmin}: {@code /api/providers/{id}/config} 404s (see the full 9-arg
     * constructor for the full wiring).
     */
    public ManagementApi(Supplier<List<String>> providerIds, AccountAdmin admin, JsonCodec json,
                          ProviderSource source, Path providersDir, ProviderRegistryHolder holder,
                          RoutingAdmin routing, QuotaAdmin quota) {
        this(providerIds, admin, json, source, providersDir, holder, routing, quota, null);
    }

    /**
     * Adds the provider-config surface ({@code GET/PUT /api/providers/{id}/config}) backed by
     * {@code config}. No {@link OAuthAdmin}: the {@code /oauth/*} routes 404 (see the full 10-arg
     * constructor for the full wiring).
     */
    public ManagementApi(Supplier<List<String>> providerIds, AccountAdmin admin, JsonCodec json,
                          ProviderSource source, Path providersDir, ProviderRegistryHolder holder,
                          RoutingAdmin routing, QuotaAdmin quota, ConfigAdmin config) {
        this(providerIds, admin, json, source, providersDir, holder, routing, quota, config, null);
    }

    /**
     * Adds the OAuth-login surface ({@code POST .../oauth/authorize}, {@code POST
     * .../oauth/complete}) backed by {@code oauth}. No {@link ProxyAdmin}: the
     * {@code /api/proxies*} routes 404 (see the full 11-arg constructor for the full wiring).
     */
    public ManagementApi(Supplier<List<String>> providerIds, AccountAdmin admin, JsonCodec json,
                          ProviderSource source, Path providersDir, ProviderRegistryHolder holder,
                          RoutingAdmin routing, QuotaAdmin quota, ConfigAdmin config, OAuthAdmin oauth) {
        this(providerIds, admin, json, source, providersDir, holder, routing, quota, config, oauth, null);
    }

    /**
     * Adds the proxy-management surface ({@code /api/proxies*}) backed by {@code proxy}. No
     * {@link ProxySource}/{@link ProxyRegistryHolder}: the proxy install/available/uninstall
     * routes 404, and an {@code ?app=} query on {@code /api/routing/model-map} always 400s (see the
     * full constructor below for the full wiring).
     */
    public ManagementApi(Supplier<List<String>> providerIds, AccountAdmin admin, JsonCodec json,
                          ProviderSource source, Path providersDir, ProviderRegistryHolder holder,
                          RoutingAdmin routing, QuotaAdmin quota, ConfigAdmin config, OAuthAdmin oauth,
                          ProxyAdmin proxy) {
        this(providerIds, admin, json, source, providersDir, holder, routing, quota, config, oauth, proxy,
                null, null, null);
    }

    /**
     * Adds the proxy install/available/uninstall surface backed by {@code proxySource}/{@code
     * proxyHolder} (jars land in {@code proxiesDir}), and makes {@code ?app=} on {@code
     * /api/routing/model-map} resolve against an installed proxy's {@link RoutingProfile} via
     * {@code proxyHolder.profileFor} instead of a hardcoded per-app table. No {@link MessagesAdmin}:
     * {@code /api/providers/{id}/messages} 404s (see the full 15-arg constructor for the full
     * wiring).
     */
    public ManagementApi(Supplier<List<String>> providerIds, AccountAdmin admin, JsonCodec json,
                          ProviderSource source, Path providersDir, ProviderRegistryHolder holder,
                          RoutingAdmin routing, QuotaAdmin quota, ConfigAdmin config, OAuthAdmin oauth,
                          ProxyAdmin proxy, ProxySource proxySource, ProxyRegistryHolder proxyHolder,
                          Path proxiesDir) {
        this(providerIds, admin, json, source, providersDir, holder, routing, quota, config, oauth,
                proxy, proxySource, proxyHolder, proxiesDir, null);
    }

    /**
     * Adds the direct-provider-chat surface ({@code POST /api/providers/{id}/messages}) backed by
     * {@code messages} -- this is how the console reaches a provider for chat: a DIRECT {@code
     * MessagesAdmin.send} call, never through a router. No {@link GithubAuth}/{@link GithubOrgScan}
     * -- the {@code /api/github*} routes 404 (see the full constructor below for the full wiring).
     */
    public ManagementApi(Supplier<List<String>> providerIds, AccountAdmin admin, JsonCodec json,
                          ProviderSource source, Path providersDir, ProviderRegistryHolder holder,
                          RoutingAdmin routing, QuotaAdmin quota, ConfigAdmin config, OAuthAdmin oauth,
                          ProxyAdmin proxy, ProxySource proxySource, ProxyRegistryHolder proxyHolder,
                          Path proxiesDir, MessagesAdmin messages) {
        this(providerIds, admin, json, source, providersDir, holder, routing, quota, config, oauth,
                proxy, proxySource, proxyHolder, proxiesDir, messages, null, null);
    }

    /**
     * Full constructor: additionally adds the GitHub-connect surface ({@code GET /api/github},
     * {@code POST /api/github/detect}, {@code POST /api/github/token}, {@code DELETE /api/github})
     * backed by {@code githubAuth} (token precedence + login validation) and {@code githubScan}
     * (whose TTL cache is invalidated after any token change so the next org scan picks it up
     * immediately, no restart required).
     */
    public ManagementApi(Supplier<List<String>> providerIds, AccountAdmin admin, JsonCodec json,
                          ProviderSource source, Path providersDir, ProviderRegistryHolder holder,
                          RoutingAdmin routing, QuotaAdmin quota, ConfigAdmin config, OAuthAdmin oauth,
                          ProxyAdmin proxy, ProxySource proxySource, ProxyRegistryHolder proxyHolder,
                          Path proxiesDir, MessagesAdmin messages, GithubAuth githubAuth,
                          GithubOrgScan githubScan) {
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
        this.proxySource = proxySource;
        this.proxyHolder = proxyHolder;
        this.messages = messages;
        this.proxiesDir = proxiesDir;
        this.githubAuth = githubAuth;
        this.githubScan = githubScan;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            route(exchange);
        } catch (Throwable e) {
            // Throwable, not RuntimeException: a provider reached through this handler (e.g. the
            // messages/discover/quota routes) can throw a LinkageError/NoClassDefFoundError, and
            // letting that escape drops the socket instead of ever writing a response. Catching it
            // here keeps the connection alive with a plain 500 JSON error carrying the throwable's
            // own message.
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "internal server error: " + e);
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
        if ("DELETE".equals(method) && seg.length == 3
                && "api".equals(seg[0]) && "providers".equals(seg[1])) {
            handleUninstall(exchange, decode(seg[2]));
            return;
        }
        if ("POST".equals(method) && seg.length == 4
                && "api".equals(seg[0]) && "providers".equals(seg[1]) && "update".equals(seg[3])) {
            handleUpdate(exchange, decode(seg[2]));
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
        if ("POST".equals(method) && seg.length == 4
                && "api".equals(seg[0]) && "providers".equals(seg[1]) && "messages".equals(seg[3])) {
            handleMessages(exchange, decode(seg[2]));
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
        if ("GET".equals(method) && seg.length == 3
                && "api".equals(seg[0]) && "proxies".equals(seg[1]) && "available".equals(seg[2])) {
            handleProxyAvailable(exchange);
            return;
        }
        if ("POST".equals(method) && seg.length == 3
                && "api".equals(seg[0]) && "proxies".equals(seg[1]) && "install".equals(seg[2])) {
            handleProxyInstall(exchange);
            return;
        }
        if ("DELETE".equals(method) && seg.length == 3
                && "api".equals(seg[0]) && "proxies".equals(seg[1])) {
            handleProxyUninstall(exchange, decode(seg[2]));
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
        if ("GET".equals(method) && seg.length == 2
                && "api".equals(seg[0]) && "github".equals(seg[1])) {
            handleGithubStatus(exchange);
            return;
        }
        if ("POST".equals(method) && seg.length == 3
                && "api".equals(seg[0]) && "github".equals(seg[1]) && "detect".equals(seg[2])) {
            handleGithubDetect(exchange);
            return;
        }
        if ("POST".equals(method) && seg.length == 3
                && "api".equals(seg[0]) && "github".equals(seg[1]) && "token".equals(seg[2])) {
            handleGithubSetToken(exchange);
            return;
        }
        if ("DELETE".equals(method) && seg.length == 2
                && "api".equals(seg[0]) && "github".equals(seg[1])) {
            handleGithubDisconnect(exchange);
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
                // The on-disk jar's file name, the reliable join key against /api/providers/available
                // (which is keyed by asset name, not id -- a provider's registered id can differ from
                // its repo/asset name, e.g. "stub" vs. "stub-auth-provider.jar").
                Path jar = holder != null ? holder.jarFor(id) : null;
                entry.put("assetName", jar != null ? jar.getFileName().toString() : null);
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
            item.put("version", entry.version);
            String installedVersion = installed ? installedVersionFor(entry) : null;
            item.put("installedVersion", installedVersion);
            item.put("updateAvailable", InstalledVersions.updateAvailable(installed, installedVersion, entry.version));
            body.add(item);
        }
        respondJson(exchange, 200, body);
    }

    // The jar actually backing entry.name in the live registry is the authoritative source for
    // "what's installed" (works even when the org asset was renamed, mirroring the installed-id
    // fallback above); a plain providersDir.resolve(entry.assetName) is the fallback for a jar
    // that exists on disk but never registered (or the registry hasn't been refreshed yet).
    private String installedVersionFor(ProviderSource.Entry entry) {
        Path jar = holder.jarFor(entry.name);
        if (jar == null) jar = providersDir.resolve(entry.assetName);
        if (!Files.exists(jar)) return null;
        return InstalledVersions.read(jar);
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

        // Targeted find() first (works even when a full list() scan is rate-limited/cached-empty);
        // fall back to scanning list() only if find() comes up empty.
        ProviderSource.Entry match = source.find(name);
        if (match == null) {
            for (ProviderSource.Entry entry : source.list()) {
                if (entry.name.equals(name)) {
                    match = entry;
                    break;
                }
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

    /** Uninstalls a provider: deletes its jar (Windows-safe close-before-delete, see
     *  {@link ProviderRegistryHolder#uninstall}) and rebuilds the live registry without it.
     *  A 404 means {@code providerId} was never installed; a 500 means it WAS installed but the
     *  jar is still present on disk after the delete attempt (e.g. a Windows sharing violation
     *  that the holder's close-then-retry couldn't clear) -- either way the caller must not treat
     *  this endpoint as having silently succeeded. */
    private void handleUninstall(HttpExchange exchange, String providerId) throws IOException {
        if (!holder.listProviderIds().contains(providerId)) {
            handleNotFound(exchange);
            return;
        }
        boolean ok = holder.uninstall(providerId, providersDir);
        if (!ok) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "uninstall failed: jar still locked");
            respondJson(exchange, 500, body);
            return;
        }
        // Purge the stored catalog entry so a later reinstall discovers fresh instead of showing
        // stale cached models (handleCatalog's read-side filter covers the response regardless,
        // but this keeps models.json itself clean).
        if (routing != null) {
            routing.removeFromCatalog(providerId);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("uninstalled", true);
        body.put("providers", holder.listProviderIds());
        respondJson(exchange, 200, body);
    }

    /**
     * Updates an installed provider to the latest org version: mirrors {@code handleUninstall}'s
     * Windows-safe sequencing (see {@link ProviderRegistryHolder#update}) -- the current registry
     * is closed BEFORE the new jar overwrites the old one, so a still-open {@code URLClassLoader}
     * never causes a sharing-violation write failure on Windows. Accounts live in the {@code
     * Store}, not the jar, so they are untouched. 404s when no install surface is wired in, when
     * {@code providerId} isn't currently installed, or when no matching org entry can be resolved
     * (e.g. the repo was deleted/renamed upstream).
     */
    private void handleUpdate(HttpExchange exchange, String providerId) throws IOException {
        if (source == null || holder == null) {
            handleNotFound(exchange);
            return;
        }
        if (!holder.listProviderIds().contains(providerId)) {
            handleNotFound(exchange);
            return;
        }

        // The provider id (e.g. "stub") often differs from its org repo/asset name (e.g.
        // "stub-auth" / "stub-auth-provider.jar"), so resolve the org entry by the installed jar's
        // asset name, which install saved under entry.assetName -- NOT by the provider id.
        Path installedJar = holder.jarFor(providerId);
        String assetName = installedJar != null ? installedJar.getFileName().toString() : null;
        ProviderSource.Entry match = null;
        if (assetName != null) {
            for (ProviderSource.Entry entry : source.list()) {
                if (assetName.equals(entry.assetName)) {
                    match = entry;
                    break;
                }
            }
            if (match == null) {
                // Full scan empty/rate-limited: derive the repo name from the "<repo>-provider.jar"
                // asset convention and do a targeted single-repo lookup.
                String repo = assetName.replaceAll("-provider\\.jar$", "");
                if (!repo.equals(assetName)) match = source.find(repo);
            }
        }
        // Last resort: id == repo name (e.g. claude-code-auth).
        if (match == null) match = source.find(providerId);
        if (match == null) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "no update source found for provider: " + providerId);
            respondJson(exchange, 404, body);
            return;
        }

        try {
            holder.update(source, match, providersDir);
        } catch (IOException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "update failed: " + e.getMessage());
            respondJson(exchange, 502, body);
            return;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("updated", true);
        body.put("version", match.version);
        body.put("providers", holder.listProviderIds());
        respondJson(exchange, 200, body);
    }

    private void handleListAccounts(HttpExchange exchange, String providerId) throws IOException {
        respondJson(exchange, 200, admin.list(providerId));
    }

    /**
     * Seeds an account from a pasted OAuth refresh token (the JVM login MVP). Body:
     * {@code {"refresh":..,"email":..,"id":..,"projectId":..,"managedProjectId":..}}: only
     * {@code refresh} plus one of {@code email}/{@code id} are required. This only becomes
     * visible to an installed provider when the server runs against a {@code FileStore} shared
     * with that provider ({@code -Dexampleserver.store=file -Dexampleserver.configDir=<dir>});
     * under the default {@code sqlite} store (or {@code memory}) it is admin-visible only.
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
        respondJson(exchange, 200, filterToInstalledProviders(json.parse(routing.catalogJson())));
    }

    // Drops any catalog entry whose key is not a currently-installed provider id -- fixes the
    // chat dropdown / routing UI showing stale models after an uninstall even for a models.json
    // that already has stale entries, with no re-discover required. Filters a fresh copy; the
    // stored models.json itself is untouched here (see RoutingAdmin#removeFromCatalog for the
    // on-disk purge done at uninstall time). Shape stays {providerId:{models,ranking}}.
    private Map<String, Object> filterToInstalledProviders(Object parsedCatalog) {
        Map<String, Object> filtered = new LinkedHashMap<>();
        if (!(parsedCatalog instanceof Map)) {
            return filtered;
        }
        List<String> installedIds = holder.listProviderIds();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) parsedCatalog).entrySet()) {
            if (e.getKey() instanceof String && installedIds.contains(e.getKey())) {
                filtered.put((String) e.getKey(), e.getValue());
            }
        }
        return filtered;
    }

    private void handleModelMapGet(HttpExchange exchange) throws IOException {
        if (routing == null) {
            handleNotFound(exchange);
            return;
        }
        try {
            RoutingProfile p = profileFromQuery(exchange);
            if (p == null) {
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("error", "select a proxy for routing");
                respondJson(exchange, 400, error);
                return;
            }
            respondJson(exchange, 200, routing.modelMapView(p));
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
            if (p == null) {
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("error", "select a proxy for routing");
                respondJson(exchange, 400, error);
                return;
            }
            Map<?, ?> body = asMap(json.parse(readBody(exchange.getRequestBody())));
            Object mapObj = body != null ? body.get("map") : null;
            if (!(mapObj instanceof Map)) {
                throw new IllegalArgumentException("body.map must be an object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) mapObj;
            respondJson(exchange, 200, routing.putModelMap(p, map));
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            respondJson(exchange, 400, error);
        }
    }

    // A missing/blank ?app= resolves to null -- there is no default/built-in routing profile
    // anymore (routing is per-installed-proxy only), so the caller must 400 "select a proxy for
    // routing" rather than falling back to one. A named app resolves against an INSTALLED PROXY's
    // own RoutingProfile (proxyHolder.profileFor) rather than a hardcoded per-app table -- an app
    // with no installed proxy (or no proxyHolder wired in at all) throws, which routes to the 400
    // path with "unknown proxy: ...".
    private RoutingProfile profileFromQuery(HttpExchange exchange) {
        String app = queryParam(exchange, "app");
        if (app == null || app.isEmpty()) return null;
        RoutingProfile p = proxyHolder != null ? proxyHolder.profileFor(app) : null;
        if (p == null) {
            throw new IllegalArgumentException("unknown proxy: " + app);
        }
        return p;
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

    // Console chat: a DIRECT provider call (MessagesAdmin.send), never through a router. The
    // provider's HttpResponse is written back VERBATIM (status/headers/body) -- unlike every other
    // route here it is NOT a {...} admin-result wrapped via respondJson, because an
    // Anthropic-messages response (or the provider's own error shape/headers) already IS the wire
    // body.
    private void handleMessages(HttpExchange exchange, String providerId) throws IOException {
        if (messages == null) {
            handleNotFound(exchange);
            return;
        }
        String body = readBody(exchange.getRequestBody());
        writeRaw(exchange, messages.send(providerId, body));
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

    private void handleProxyAvailable(HttpExchange exchange) throws IOException {
        if (proxySource == null || proxyHolder == null) {
            handleNotFound(exchange);
            return;
        }
        List<Map<String, Object>> body = new ArrayList<>();
        Set<String> installedIds = new HashSet<>(proxyHolder.listProxyIds());
        for (ProxySource.Entry entry : proxySource.list()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", entry.name);
            item.put("assetName", entry.assetName);
            boolean installed = Files.exists(proxiesDir.resolve(entry.assetName))
                    || installedIds.contains(entry.name);
            item.put("installed", installed);
            body.add(item);
        }
        respondJson(exchange, 200, body);
    }

    private void handleProxyInstall(HttpExchange exchange) throws IOException {
        if (proxySource == null || proxyHolder == null) {
            handleNotFound(exchange);
            return;
        }
        Object parsed = json.parse(readBody(exchange.getRequestBody()));
        String name = null;
        if (parsed instanceof Map) {
            Object nameObj = ((Map<?, ?>) parsed).get("name");
            if (nameObj instanceof String) {
                name = (String) nameObj;
            }
        }

        // Targeted find() first (works even when a full list() scan is rate-limited/cached-empty);
        // fall back to scanning list() only if find() comes up empty.
        ProxySource.Entry match = proxySource.find(name);
        if (match == null) {
            for (ProxySource.Entry entry : proxySource.list()) {
                if (entry.name.equals(name)) {
                    match = entry;
                    break;
                }
            }
        }
        if (match == null) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "unknown proxy");
            respondJson(exchange, 404, body);
            return;
        }

        try {
            proxySource.download(match, proxiesDir);
        } catch (IOException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "download failed: " + e.getMessage());
            respondJson(exchange, 502, body);
            return;
        }
        proxyHolder.refresh(proxiesDir);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("installed", true);
        body.put("proxies", proxyHolder.listProxyIds());
        respondJson(exchange, 200, body);
    }

    /** Uninstalls a proxy: deletes its jar (Windows-safe close-before-delete, see
     *  {@link ProxyRegistryHolder#uninstall}) and rebuilds the live registry without it. */
    private void handleProxyUninstall(HttpExchange exchange, String proxyId) throws IOException {
        if (proxySource == null || proxyHolder == null) {
            handleNotFound(exchange);
            return;
        }
        boolean ok = proxyHolder.uninstall(proxyId, proxiesDir);
        if (!ok) {
            handleNotFound(exchange);
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("uninstalled", true);
        body.put("proxies", proxyHolder.listProxyIds());
        respondJson(exchange, 200, body);
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

    // -- GitHub connect (console-driven token for the org scan) --
    //
    // SECURITY: none of these three handlers ever put the token itself into a response body --
    // githubStatusBody() below is the ONLY place a github status map is built, and it exposes only
    // source/connected/login/rateLimitRemaining. A manually POSTed token is written into GithubAuth
    // (in-memory only) and never echoed back.

    private void handleGithubStatus(HttpExchange exchange) throws IOException {
        if (githubAuth == null) {
            handleNotFound(exchange);
            return;
        }
        respondJson(exchange, 200, githubStatusBody());
    }

    private void handleGithubDetect(HttpExchange exchange) throws IOException {
        if (githubAuth == null) {
            handleNotFound(exchange);
            return;
        }
        boolean detected = githubAuth.detectFromGhCli();
        if (githubScan != null) githubScan.invalidateCache();
        Map<String, Object> body = githubStatusBody();
        body.put("detected", detected);
        respondJson(exchange, 200, body);
    }

    private void handleGithubSetToken(HttpExchange exchange) throws IOException {
        if (githubAuth == null) {
            handleNotFound(exchange);
            return;
        }
        Map<?, ?> body = asMap(json.parse(readBody(exchange.getRequestBody())));
        String token = stringField(body, "token");
        githubAuth.setManualToken(token);
        if (githubScan != null) githubScan.invalidateCache();
        respondJson(exchange, 200, githubStatusBody());
    }

    private void handleGithubDisconnect(HttpExchange exchange) throws IOException {
        if (githubAuth == null) {
            handleNotFound(exchange);
            return;
        }
        githubAuth.setManualToken(null);
        if (githubScan != null) githubScan.invalidateCache();
        respondJson(exchange, 200, githubStatusBody());
    }

    // {connected, source, login, rateLimitRemaining} -- NEVER a token field. rateLimitRemaining is
    // always null (skipped: an extra GitHub API round trip isn't worth it just for a status line).
    private Map<String, Object> githubStatusBody() {
        String login = githubAuth.validateLogin();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("connected", login != null);
        body.put("source", githubAuth.source());
        body.put("login", login);
        body.put("rateLimitRemaining", null);
        return body;
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

    // Writes a provider's HttpResponse back VERBATIM: status, every header (content-length is
    // recomputed from the actual byte count, never copied), and body -- mirrors
    // ExampleServer/ProxyServer's own writeResponse, since this is the one route whose response
    // body is not a JSON map this class built itself.
    private void writeRaw(HttpExchange exchange, HttpResponse resp) throws IOException {
        if (resp.headers != null) {
            for (Map.Entry<String, String> e : resp.headers.entrySet()) {
                if (e.getKey() != null && e.getValue() != null
                        && !"content-length".equalsIgnoreCase(e.getKey())) {
                    exchange.getResponseHeaders().set(e.getKey(), e.getValue());
                }
            }
        }
        byte[] body = (resp.body != null ? resp.body : "").getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(resp.status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }
}
