package io.github.intisy.ai.exampleserver;

import io.github.intisy.ai.jvm.AiJava;
import io.github.intisy.ai.jvm.Storage;
import io.github.intisy.ai.shared.routing.RoutingProfile;
import io.github.intisy.ai.shared.spi.Store;
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

/** Boots the full {@link ExampleServer} (no {@code /api} handler needed for this check) and drives
 *  {@code GET /} over loopback: it must serve the self-contained dashboard HTML, not the router. */
class DashboardIntegrationTest {

    private static final String CONFIG_FILE = "dashboard-routing.json";

    private AiJava ai;
    private ExampleServer server;

    @BeforeEach
    void setUp() {
        String providersDir = System.getProperty("exampleserver.providersDir");
        assertTrue(providersDir != null && !providersDir.isEmpty(),
                "exampleserver.providersDir must be set by the Gradle test task");
        ai = AiJava.builder().storage(Storage.memory()).providersDir(Paths.get(providersDir)).build();
        Store store = ai.store();
        ServerSeeds.seedEcho(store, ai.jsonCodec(), CONFIG_FILE);
        RoutingProfile profile = ServerProfile.echoTiers(CONFIG_FILE);
        server = ExampleServer.start(ai, profile, 0); // ephemeral port
    }

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) server.stop();
        if (ai != null) ai.close();
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
    void routedPathsStillWorkAlongsideDashboard() throws IOException {
        Response models = get("/v1/models");
        assertEquals(200, models.status);
        assertTrue(models.body.contains("m-echo-haiku"), models.body);

        Response health = get("/healthz");
        assertEquals(200, health.status);
        assertTrue(health.body.contains("ok"), health.body);
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
        assertTrue(html.contains("passthrough — all provider models"), "neutral passthrough hint text missing");
        assertTrue(html.contains("proxy-hint"), "neutral proxy-hint class missing");
        assertTrue(html.contains("function renderPassthroughCatalog("), "passthrough read-only catalog view missing");
        assertTrue(html.contains("Passthrough — exposes all provider models"), "passthrough view heading missing");
        assertTrue(html.contains("row.routing === false"), "selectProxy must branch on routing === false");
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
