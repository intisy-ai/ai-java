package io.github.intisy.ai.exampleserver.web;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * String-contains checks on the dashboard's classpath resource ({@code /dashboard/index.html}),
 * proving the update-detection UI pieces from the E-J brief actually shipped in the page: the
 * server call the Update button makes, the version badge, and the button itself. Mirrors the
 * "the dashboard is a single self-contained resource, not a build output" approach the rest of
 * this server's tests take (see {@link Dashboard}) -- no browser, just the raw HTML/JS text.
 */
class DashboardUpdateMarkersTest {

    private static String loadDashboardHtml() throws IOException {
        try (InputStream in = Dashboard.class.getResourceAsStream("/dashboard/index.html")) {
            assertTrue(in != null, "missing classpath resource /dashboard/index.html");
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) != -1) {
                buf.write(chunk, 0, n);
            }
            return buf.toString(StandardCharsets.UTF_8.name());
        }
    }

    @Test
    void dashboardCallsTheUpdateEndpoint() throws IOException {
        String html = loadDashboardHtml();
        assertTrue(html.contains("/update"), "the Update button must POST .../update");
        assertTrue(html.contains("function updateProvider"), "an updateProvider handler must exist");
        assertTrue(html.contains("encodeURIComponent(id) + \"/update\""),
                "the update call must hit /api/providers/{id}/update");
    }

    @Test
    void dashboardRendersAnUpdateButtonAndVersionBadge() throws IOException {
        String html = loadDashboardHtml();
        assertTrue(html.contains("updateAvailable"), "the row model must carry updateAvailable");
        assertTrue(html.contains("Update to v"), "the button label must show the target version");
        assertTrue(html.contains("prov-version"), "the installed version badge must have its own CSS class");
        assertTrue(html.contains("installedVersion"), "the row model must carry the installed version");
    }

    @Test
    void dashboardNeverUsesAnEmDash() throws IOException {
        String html = loadDashboardHtml();
        assertTrue(!html.contains("—"), "no em dashes allowed anywhere in the dashboard");
    }

    // E-K: the installed<->available join must be by asset name (the jar on disk), not by
    // assuming a provider's registered id matches its org repo name -- that assumption breaks for
    // stub/antigravity (id "stub"/"antigravity" vs. repo "stub-auth"/"antigravity-auth").
    @Test
    void mergeProviderRowsJoinsByAssetNameNotById() throws IOException {
        String html = loadDashboardHtml();
        assertTrue(html.contains("availableByAsset"), "the available index must be keyed by assetName");
        assertTrue(html.contains("availableByAsset[p.assetName]"),
                "installed rows must be looked up by their own assetName");
        assertTrue(!html.contains("availableByName[p.id]"),
                "the old id-based join must be gone");
    }

    // The name line must show the REPO name (e.g. "stub-auth"), falling back to the bare id only
    // when no available-list match exists.
    @Test
    void nameLineRendersTheRepoNameFallingBackToId() throws IOException {
        String html = loadDashboardHtml();
        assertTrue(html.contains("el(\"div\", \"prov-name\", row.name || row.id)"),
                "the name line must prefer the repo name over the bare id");
    }

    // E-L: an empty provider list gave no reason -- an anonymous org scan hits GitHub's 60
    // req/hour limit and returns empty with no explanation. The empty-state row must now hint at
    // the fix (GITHUB_TOKEN/GH_TOKEN) instead of just saying nothing was found.
    @Test
    void emptyProviderListHintsAtTheGithubTokenCause() throws IOException {
        String html = loadDashboardHtml();
        assertTrue(html.contains("GITHUB_TOKEN"), "the empty-state row must mention GITHUB_TOKEN");
        assertTrue(html.contains("No providers found."), "the empty-state row must still read as a clear empty state");
    }

    // E-M: connect a GitHub token from the console (gh CLI auto-detect or manual) so the org scan
    // is authenticated instead of anonymously rate-limited.
    @Test
    void dashboardRendersTheGithubConnectPanel() throws IOException {
        String html = loadDashboardHtml();
        assertTrue(html.contains("id=\"github-card\""), "a dedicated GitHub panel must exist");
        assertTrue(html.contains("id=\"github-detect-button\""), "a Detect from gh CLI button must exist");
        assertTrue(html.contains("id=\"github-token-input\""), "a manual token input field must exist");
        assertTrue(html.contains("id=\"github-save-token-button\""), "a Save button for the manual token must exist");
        assertTrue(html.contains("id=\"github-disconnect-button\""), "a Disconnect button must exist");
        assertTrue(html.contains("id=\"github-status\""), "a status line must exist");
    }

    @Test
    void dashboardGithubTokenInputIsAPasswordField() throws IOException {
        String html = loadDashboardHtml();
        assertTrue(html.contains("type=\"password\" id=\"github-token-input\""),
                "the manual token field must never render its value as plain text");
    }

    @Test
    void dashboardCallsTheGithubDetectAndTokenEndpoints() throws IOException {
        String html = loadDashboardHtml();
        assertTrue(html.contains("/api/github/detect"), "the Detect button must POST /api/github/detect");
        assertTrue(html.contains("/api/github/token"), "Save must POST /api/github/token");
        assertTrue(html.contains("function detectGithub"), "a detectGithub handler must exist");
        assertTrue(html.contains("function saveGithubToken"), "a saveGithubToken handler must exist");
        assertTrue(html.contains("function disconnectGithub"), "a disconnectGithub handler must exist");
    }

    @Test
    void dashboardLoadsGithubStatusOnPageLoad() throws IOException {
        String html = loadDashboardHtml();
        assertTrue(html.contains("jsonFetch(\"/api/github\")"), "the status line must GET /api/github");
        assertTrue(html.contains("loadGithubStatus();"), "loadGithubStatus must be called on init");
    }

    @Test
    void dashboardNeverRendersTheTokenValueItself() throws IOException {
        String html = loadDashboardHtml();
        // renderGithubStatus must only ever read connected/source/login -- never a "token" field
        // off the status response, and it must never assign a fetched value into the token input.
        assertTrue(!html.contains("status.token"), "the status renderer must never read a token field");
        assertTrue(!html.contains("github-token-input\").value = status"),
                "the token input must never be populated FROM a server response");
    }
}
