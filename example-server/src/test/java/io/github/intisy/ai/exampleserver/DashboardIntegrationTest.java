package io.github.intisy.ai.exampleserver;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Paths;
import java.util.Scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Boots the built-in {@link ExampleServer} (no {@code /api} handler needed for these checks, and
 *  no router/{@code /v1} at all -- see the class javadoc) and drives {@code GET /} over loopback:
 *  it must serve the self-contained dashboard HTML. */
class DashboardIntegrationTest {

    private ExampleServer server;

    @BeforeEach
    void setUp() {
        server = ExampleServer.start(0, null); // ephemeral port; dashboard + healthz only
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    void rootServesSelfContainedDashboardHtml() throws IOException {
        Response r = get("/");
        assertEquals(200, r.status);
        assertTrue(r.contentType != null && r.contentType.contains("text/html"), r.contentType);
        assertTrue(r.body.contains("<title>AI Java · Provider Console</title>"), r.body);
        assertTrue(r.body.contains("Providers"), r.body);
        assertTrue(r.body.contains("Install"), r.body);
        assertTrue(r.body.contains("Refresh"), r.body);
        assertTrue(r.body.contains("Accounts"), r.body);
        assertTrue(r.body.contains("Chat"), r.body);
        assertFalse(r.body.contains("src=\"http"), "must not reference external scripts/images: " + r.body);
        assertFalse(r.body.contains("href=\"http"), "must not reference external stylesheets/links: " + r.body);
    }

    @Test
    void healthzWorksAlongsideDashboardAndBuiltInV1IsGone() throws IOException {
        Response health = get("/healthz");
        assertEquals(200, health.status);
        assertTrue(health.body.contains("ok"), health.body);

        // ExampleServer carries no router/`/v1` of its own anymore (console chat is a DIRECT
        // provider call via /api/providers/{id}/messages) -- both must 404, same as any other
        // unknown path under the dashboard's "/" context.
        assertEquals(404, get("/v1/models").status);
        assertEquals(404, get("/v1/messages").status);
    }

    @Test
    void unknownPathUnderRootIs404() throws IOException {
        Response r = get("/not-a-real-page");
        assertEquals(404, r.status);
    }

    @Test
    void dashboardIncludesConfigCard() throws IOException {
        Response r = get("/");
        assertTrue(r.body.contains("id=\"config-card\""), "config card missing");
        assertTrue(r.body.contains("id=\"config-body\""), "config body missing");
    }

    @Test
    void dashboardIncludesLoginButton() throws IOException {
        Response r = get("/");
        assertTrue(r.body.contains("id=\"oauth-login-button\""), "login button missing");
    }

    @Test
    void dashboardIncludesOauthPasteBox() throws IOException {
        String html = get("/").body;
        assertTrue(html.contains("id=\"oauth-code-input\""), "oauth paste box missing");
        assertTrue(html.contains("id=\"oauth-complete-button\""), "oauth complete button missing");
    }

    @Test
    void dashboardIncludesProxiesCard() throws IOException {
        String html = get("/").body;
        assertTrue(html.contains("id=\"proxies-card\""), "proxies card missing");
        assertTrue(html.contains("id=\"proxies-body\""), "proxies body missing");
    }

    @Test
    void routingIsNestedUnderProxies() throws IOException {
        String html = get("/").body;
        assertFalse(html.contains("id=\"routing-card\""), "standalone routing card should be removed");
        assertTrue(html.contains("id=\"proxy-settings\""), "proxy settings panel missing");
    }

    @Test
    void routingAppSelectorMovedIntoProxySettings() throws IOException {
        String html = get("/").body;
        assertFalse(html.contains("id=\"routing-app-select\""), "standalone routing app selector should be removed");
        assertTrue(html.contains("selectProxy"), "proxy row selection handler missing");
    }

    @Test
    void headerHasProviderConsoleTitle() throws IOException {
        String html = get("/").body;
        assertTrue(html.contains("AI Java · Provider Console"), "new title missing");
    }

    @Test
    void addAccountIsFallbackOnly() throws IOException {
        String html = get("/").body;
        assertTrue(html.contains("id=\"add-account-fallback\""), "fallback affordance missing");
        assertFalse(html.contains("id=\"toggle-add-account-button\""), "manual-add toggle should be removed");
    }

    @Test
    void accountsAreClickableWithDetailPanel() throws IOException {
        String html = get("/").body;
        assertTrue(html.contains("id=\"account-detail\""), "account detail panel missing");
        assertTrue(html.contains("id=\"provider-quota\""), "combined quota summary missing");
    }

    @Test
    void installedProviderRowsHaveAnUninstallAffordance() throws IOException {
        String html = get("/").body;
        assertTrue(html.contains("uninstallProvider"), "uninstall affordance missing");
    }

    @Test
    void proxiesCardHasInstallAvailableAndUninstallSurface() throws IOException {
        String html = get("/").body;
        assertTrue(html.contains("/api/proxies/available"), "proxies available scan URL missing");
        assertTrue(html.contains("/api/proxies/install"), "proxies install URL missing");
        assertTrue(html.contains("function installProxy("), "installProxy handler missing");
        assertTrue(html.contains("function uninstallProxy("), "uninstallProxy handler missing");
    }

    @Test
    void proxyRowRenderingUsesStableIdNotDisplayName() throws IOException {
        String html = get("/").body;
        assertTrue(html.contains("el(\"span\", \"proxy-app\", row.id)"),
                "installed proxy row label must be the stable row.id");
        assertFalse(html.contains("row.displayName"), "proxy rows must not read row.displayName anymore");
        assertTrue(html.contains("row.routing"), "proxy rows must gate on row.routing");
    }

    @Test
    void availableProxyRowLabelStripsTrailingProxySuffix() throws IOException {
        String html = get("/").body;
        assertTrue(html.contains("function stripProxySuffix("), "stripProxySuffix helper missing");
        assertTrue(html.contains("stripProxySuffix(item.name)"),
                "available proxy rows must derive their label via stripProxySuffix");
    }

    @Test
    void configRendersGroupedWithSelectFieldSupportAndFlatFallback() throws IOException {
        String html = get("/").body;
        assertTrue(html.contains("config-group-title"), "grouped config header class missing");
        assertTrue(html.contains("field.type === \"select\""), "select field type not handled in config rendering");
        assertTrue(html.contains("singleUnnamed"), "single-unnamed-group header suppression missing");
        assertTrue(html.contains("resp.fields"), "flat/older {fields:[...]} config shape fallback missing");
    }

    @Test
    void passthroughProxyIsSelectableWithNeutralHintAndReadOnlyCatalog() throws IOException {
        String html = get("/").body;
        assertFalse(html.contains("native / no routing"), "old alarming passthrough hint text must be gone");
        assertTrue(html.contains("proxy-hint"), "neutral proxy-hint class missing");
        assertTrue(html.contains("function renderPassthroughCatalog("), "passthrough read-only catalog view missing");
        assertTrue(html.contains("Passthrough (all provider models)"), "passthrough view heading missing");
        assertTrue(html.contains("row.routing === false"), "selectProxy must branch on routing === false");
    }

    // A passthrough proxy row (row.routing === false, e.g. opencode) shows a one-word
    // "passthrough" hint; a routable proxy row (row.routing === true, e.g. claude-code) shows the
    // matching one-word "routing" hint in the same spot -- both kinds get a label there now.
    @Test
    void proxyRowsShowOneWordRoutingHints() throws IOException {
        String html = get("/").body;
        assertTrue(html.contains("el(\"span\", \"proxy-hint\", \"passthrough\")"), "one-word passthrough hint missing");
        assertTrue(html.contains("el(\"span\", \"proxy-hint\", \"routing\")"), "one-word routing hint missing");
    }

    // "Discover all models" fetches the installed provider list, then discovers each one's
    // models (non-fatal per provider), and refreshes the chat dropdown + routing view afterward.
    @Test
    void discoverAllModelsButtonIteratesInstalledProviders() throws IOException {
        String html = get("/").body;
        assertTrue(html.contains("id=\"discover-all-models-button\""), "discover-all-models button missing");
        assertTrue(html.contains("function discoverAllModels("), "discoverAllModels handler missing");
        assertTrue(html.contains("discover-all-models-button\").addEventListener(\"click\", discoverAllModels)"),
                "discover-all-models button must be wired to discoverAllModels");
        assertTrue(html.contains("jsonFetch(\"/api/providers\")"), "discoverAllModels must fetch installed providers");
        assertTrue(html.contains("\"/models/discover\""), "discoverAllModels must POST .../models/discover per provider");
        assertTrue(html.contains("Promise.all([loadModels(), loadRouting()])"),
                "discoverAllModels must refresh both loadModels() and loadRouting() afterward");
    }

    @Test
    void dashboardHasNoEmDashes() throws IOException {
        String html = get("/").body;
        assertFalse(html.contains("—"), "dashboard HTML must not contain an em dash");
    }

    @Test
    void installedRenderIsDecoupledFromAvailableScanForBothCards() throws IOException {
        String html = get("/").body;
        // Stable markers proving the installed list renders independently of the (slower,
        // org-scanning) available fetch, so a failed/slow scan can't blank the installed rows.
        assertTrue(html.contains("function fetchInstalledProviders("), "providers installed-fetch not decoupled");
        assertTrue(html.contains("function fetchAvailableProviders("), "providers available-fetch not decoupled");
        assertTrue(html.contains("function fetchInstalledProxies("), "proxies installed-fetch not decoupled");
        assertTrue(html.contains("function fetchAvailableProxies("), "proxies available-fetch not decoupled");
        assertTrue(html.contains("id=\"providers-scan-error\""), "providers scan-error hook missing");
        assertTrue(html.contains("id=\"proxies-scan-error\""), "proxies scan-error hook missing");
    }

    @Test
    void sendChatPayloadIncludesMaxTokens() throws IOException {
        String html = get("/").body;
        assertTrue(html.contains("CHAT_MAX_TOKENS"), "CHAT_MAX_TOKENS constant missing");
        assertTrue(html.contains("max_tokens: CHAT_MAX_TOKENS"), "send payload must include max_tokens");
    }

    // Console chat = a DIRECT provider call, never a router match: the model dropdown must source
    // from the routing catalog (provider-keyed) rather than a router's /v1/models, and sendChat
    // must post straight to the selected option's own provider.
    @Test
    void chatModelDropdownIsCatalogSourcedAndSendsDirectlyToProvider() throws IOException {
        String html = get("/").body;
        assertFalse(html.contains("jsonFetch(\"/v1/models\")"), "loadModels must not call the removed /v1/models");
        assertTrue(html.contains("jsonFetch(\"/api/routing/catalog\")"),
                "loadModels must source the chat dropdown from /api/routing/catalog");
        assertTrue(html.contains("opt.dataset.provider = providerId"),
                "each model option must record its own provider");
        assertFalse(html.contains("jsonFetch(\"/v1/messages\""), "sendChat must not post to the removed /v1/messages");
        assertTrue(html.contains("\"/api/providers/\" + encodeURIComponent(provider) + \"/messages\""),
                "sendChat must POST straight to the selected model's own provider");
    }

    @Test
    void accountAddAndOauthCompleteAutoDiscoverAndRefreshModels() throws IOException {
        String html = get("/").body;
        assertTrue(html.contains("function autoDiscoverAndRefresh("), "autoDiscoverAndRefresh helper missing");
        assertTrue(html.contains("autoDiscoverAndRefresh(selectedProviderId)"),
                "autoDiscoverAndRefresh must be wired into account-add / OAuth-complete");
    }

    @Test
    void manualDiscoverRefreshesChatModelDropdown() throws IOException {
        String html = get("/").body;
        // discoverModels' success handler must refresh both the routing view and the chat
        // model dropdown (previously only loadRouting() ran, leaving chat stale until refresh).
        assertTrue(html.contains("Promise.all([loadModels(), loadRouting()])"),
                "manual discover path must call loadModels() alongside loadRouting()");
    }

    @Test
    void bannerDoesNotHardcodeAModelName() throws IOException {
        // ServerMain.main() blocks forever (joins the running thread), so this is a source-grep
        // rather than an exercised-output assertion; Gradle's test task workingDir defaults to
        // this module's projectDir, so the relative path resolves under `gradlew :example-server:test`.
        String source = new String(java.nio.file.Files.readAllBytes(
                Paths.get("src/main/java/io/github/intisy/ai/exampleserver/ServerMain.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(source.contains("claude-haiku-4"),
                "ServerMain banner must not hardcode a real model name");
        assertTrue(source.contains("<model-id>"), "banner should use a generic placeholder");
    }

    // -- tiny loopback HTTP client (test-only; newer JDK APIs allowed in tests) --

    private Response get(String path) throws IOException {
        URL url = new URL("http://127.0.0.1:" + server.port() + path);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("GET");
        int status = c.getResponseCode();
        String contentType = c.getContentType();
        InputStream is = status < 400 ? c.getInputStream() : c.getErrorStream();
        String text = "";
        if (is != null) {
            try (Scanner s = new Scanner(is, "UTF-8").useDelimiter("\\A")) {
                text = s.hasNext() ? s.next() : "";
            }
        }
        return new Response(status, text, contentType);
    }

    private static final class Response {
        final int status;
        final String body;
        final String contentType;
        Response(int status, String body, String contentType) {
            this.status = status;
            this.body = body;
            this.contentType = contentType;
        }
    }
}
