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
