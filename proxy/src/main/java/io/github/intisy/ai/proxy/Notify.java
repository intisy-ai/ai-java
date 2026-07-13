package io.github.intisy.ai.proxy;

import com.google.gson.Gson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Default {@code notify} sink: appends one JSONL line per notice to
 * {@code <configDir>/cache/auth-notifications.jsonl} — the queue core-auth's PostToolUse
 * hook drains into a user-visible systemMessage/toast. Repeats of the same message are
 * throttled to once per {@link #NOTIFY_INTERVAL_MS} so a hot fallback loop can't spam the
 * queue. Java port of {@code libs/core-proxy/src/server.ts}'s {@code defaultNotify}.
 */
public final class Notify {

    private static final long NOTIFY_INTERVAL_MS = 60_000L;
    private static final Gson GSON = new Gson();

    private final String configDir;
    private final Consumer<String> log;
    // Per-instance (per-server) throttle state, mirroring the JS closure's lastNotified map.
    private final Map<String, Long> lastNotified = new ConcurrentHashMap<>();

    public Notify(String configDir, Consumer<String> log) {
        this.configDir = configDir;
        this.log = log != null ? log : s -> {
        };
    }

    public void notify(String message, String level) {
        try {
            long now = System.currentTimeMillis();
            Long last = lastNotified.get(message);
            if (last != null && now - last < NOTIFY_INTERVAL_MS) return;
            lastNotified.put(message, now);

            Path dir = Paths.get(configDir, "cache");
            if (!Files.exists(dir)) Files.createDirectories(dir);

            Map<String, Object> line = new LinkedHashMap<>();
            line.put("message", message);
            line.put("level", level != null ? level : "warning");
            line.put("at", now);
            String json = GSON.toJson(line) + "\n";
            Files.write(dir.resolve("auth-notifications.jsonl"), json.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            log.accept("notify: " + message);
        } catch (Exception ignored) {
            // swallow-all, mirrors server.ts's defaultNotify try/catch
        }
    }
}
