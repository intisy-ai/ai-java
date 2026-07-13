package io.github.intisy.ai.jvm;

import com.google.gson.Gson;
import io.github.intisy.ai.shared.logic.Notifier;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link Notifier}: appends one JSONL line per notice to
 * {@code <configFolder>/../cache/auth-notifications.jsonl} — the queue core-auth's
 * PostToolUse hook drains into a user-visible systemMessage/toast. Repeats of the same
 * message are throttled to once per {@link #NOTIFY_INTERVAL_MS} so a hot fallback loop
 * can't spam the queue.
 *
 * <p>Reference: the old {@code proxy} module's {@code Notify}
 * ({@code proxy/src/main/java/.../Notify.java}, itself a port of
 * {@code libs/core-proxy/src/server.ts}'s {@code defaultNotify}) — same JSONL shape
 * ({@code {message, level, at}}), now implementing {@code shared}'s {@link Notifier}
 * callback instead of the old JVM-only signature.
 */
public class JsonlNotifier implements Notifier {

    private static final long NOTIFY_INTERVAL_MS = 60_000L;
    private static final Gson GSON = new Gson();

    private final Path cacheDir;
    // Per-instance throttle state, mirroring the JS closure's lastNotified map.
    private final Map<String, Long> lastNotified = new ConcurrentHashMap<>();

    /**
     * @param configFolder the SAME base directory passed to {@link FileStore}; notifications
     *                      are written to its sibling {@code cache} directory.
     */
    public JsonlNotifier(Path configFolder) {
        this.cacheDir = configFolder.resolveSibling("cache");
    }

    @Override
    public void notify(String message, String level) {
        try {
            long now = System.currentTimeMillis();
            Long last = lastNotified.get(message);
            if (last != null && now - last < NOTIFY_INTERVAL_MS) return;
            lastNotified.put(message, now);

            if (!Files.exists(cacheDir)) Files.createDirectories(cacheDir);

            Map<String, Object> line = new LinkedHashMap<>();
            line.put("message", message);
            line.put("level", level != null ? level : "warning");
            line.put("at", now);
            String json = GSON.toJson(line) + "\n";
            Files.write(cacheDir.resolve("auth-notifications.jsonl"), json.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // swallow-all, mirrors the old Notify's defaultNotify try/catch
        }
    }
}
