package io.github.intisy.ai.jvm.backend.http;

import io.github.intisy.ai.shared.spi.HttpClient;
import io.github.intisy.ai.shared.spi.http.HttpRequest;
import io.github.intisy.ai.shared.spi.http.HttpResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code HttpURLConnection}-backed {@link HttpClient}: the real JVM implementation of the
 * HTTP boundary SPI (blocking-shaped; no extra HTTP client dependency needed). Reference:
 * the old {@code core} module's {@code UrlConnectionFetcher}
 * ({@code core/src/main/java/.../manager/UrlConnectionFetcher.java}, used for the OAuth
 * token-refresh POST) — generalized here to any method/headers/body, not just form POSTs.
 */
public class UrlConnectionHttpClient implements HttpClient {
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 15_000;

    @Override
    public HttpResponse send(HttpRequest req) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(req.url).openConnection();
            conn.setRequestMethod(req.method != null ? req.method : "GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            if (req.headers != null) {
                for (Map.Entry<String, String> e : req.headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            if (req.body != null && !req.body.isEmpty()) {
                conn.setDoOutput(true);
                byte[] body = req.body.getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body);
                }
            }

            int status = conn.getResponseCode();
            InputStream stream = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
            String responseBody = stream != null ? readAll(stream) : "";

            HttpResponse resp = new HttpResponse();
            resp.status = status;
            resp.headers = headersOf(conn);
            resp.body = responseBody;
            return resp;
        } catch (IOException e) {
            throw new RuntimeException("HTTP request failed: " + req.url, e);
        }
    }

    private static Map<String, String> headersOf(HttpURLConnection conn) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : conn.getHeaderFields().entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue().isEmpty()) continue;
            headers.put(e.getKey(), e.getValue().get(0));
        }
        return headers;
    }

    private static String readAll(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = is.read(chunk)) != -1) buffer.write(chunk, 0, n);
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }
}
