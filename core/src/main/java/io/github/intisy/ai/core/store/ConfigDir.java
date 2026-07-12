package io.github.intisy.ai.core.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Filesystem location resolution, mirroring the JS {@code getConfigDir}/{@code configFolder}
 * (see {@code libs/core-auth/src/env.ts}) so a Java process and a JS client AGREE on the
 * config directory when sharing a {@code HUB_CONFIG_DIR}.
 *
 * Precedence: {@code HUB_CONFIG_DIR} (the loader's forced dir) &gt; the active app's own
 * native var ({@code CLAUDE_CONFIG_DIR} / {@code OPENCODE_CONFIG_DIR}|{@code XDG_CONFIG_HOME})
 * &gt; filesystem fallback.
 */
public final class ConfigDir {
    private ConfigDir() {
    }

    private static String trimmedEnv(String name) {
        String v = System.getenv(name);
        return v != null && !v.trim().isEmpty() ? v.trim() : "";
    }

    /** Mirrors env.ts's {@code activeApp()}: CORE_APP override, else best-effort argv sniff. */
    private static boolean isClaude() {
        String override = System.getenv("CORE_APP");
        if ("claude".equals(override)) return true;
        if ("opencode".equals(override)) return false;
        String cmd = System.getProperty("sun.java.command", "");
        return cmd.toLowerCase().contains("claude");
    }

    /**
     * Resolves the active app's config dir: {@code HUB_CONFIG_DIR} if set/non-blank, else the
     * app-home fallback (mirrors env.ts {@code getConfigDir}).
     */
    public static String resolve() {
        String forced = trimmedEnv("HUB_CONFIG_DIR");
        if (!forced.isEmpty()) return forced;

        String home = System.getProperty("user.home");

        if (isClaude()) {
            String hubClaudeDir = trimmedEnv("HUB_CLAUDE_DIR");
            if (!hubClaudeDir.isEmpty()) return hubClaudeDir;
            String claudeConfigDir = trimmedEnv("CLAUDE_CONFIG_DIR");
            if (!claudeConfigDir.isEmpty()) return claudeConfigDir;
            Path claudeHome = Paths.get(home, ".claude");
            return Files.exists(claudeHome) ? claudeHome.toString() : Paths.get(home, ".config", "claude").toString();
        }

        String hubOpencodeDir = trimmedEnv("HUB_OPENCODE_DIR");
        if (!hubOpencodeDir.isEmpty()) return hubOpencodeDir;
        String opencodeConfigDir = trimmedEnv("OPENCODE_CONFIG_DIR");
        if (!opencodeConfigDir.isEmpty()) return opencodeConfigDir;
        String xdg = trimmedEnv("XDG_CONFIG_HOME");
        if (!xdg.isEmpty()) return Paths.get(xdg, "opencode").toString();
        Path opencodeConfigHome = Paths.get(home, ".config", "opencode");
        return Files.exists(opencodeConfigHome) ? opencodeConfigHome.toString() : Paths.get(home, ".opencode").toString();
    }

    /** {@code <configDir>/config} — where the account store and per-plugin configs live. */
    public static Path configFolder() {
        return Paths.get(resolve(), "config");
    }
}
