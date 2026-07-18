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
}
