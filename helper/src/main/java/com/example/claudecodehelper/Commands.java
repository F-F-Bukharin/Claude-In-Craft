package com.example.claudecodehelper;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Finding {@code claude} and building its locked-down command line. */
public final class Commands {
    static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");

    private Commands() {}

    /**
     * The configured path; else claude.exe or claude on PATH; else the native installer's ~/.local/bin. Null when
     * none exists. An npm install's {@code claude.cmd} isn't picked up: pass its path, or use the native installer.
     */
    public static String find(String configured, String pathEnv, Path home, boolean windows) {
        if (configured != null && !configured.isBlank()) return configured.strip();
        List<String> names = windows ? List.of("claude.exe", "claude") : List.of("claude");
        for (String dir : (pathEnv == null ? "" : pathEnv).split(File.pathSeparator)) {
            if (dir.isBlank()) continue;
            for (String name : names) {
                try {
                    Path p = Path.of(dir.strip(), name);
                    if (Files.isRegularFile(p)) return p.toString();
                } catch (RuntimeException ignored) {
                    // a malformed PATH entry
                }
            }
        }
        Path local = home.resolve(".local").resolve("bin").resolve(windows ? "claude.exe" : "claude");
        return Files.isRegularFile(local) ? local.toString() : null;
    }

    public static String find(String configured) {
        return find(configured, System.getenv("PATH"), Path.of(System.getProperty("user.home")), WINDOWS);
    }

    /**
     * A warm session: stream-json in and out, no tools, no settings, no MCP, no slash commands, nothing saved, and
     * Claude Code's system prompt replaced by {@code systemFile}. Never {@code --bare}: it ignores the subscription login.
     */
    public static List<String> stream(String exe, Options o, Path systemFile, boolean windows) {
        List<String> cmd = new ArrayList<>(List.of(exe, "-p", "--input-format", "stream-json", "--output-format",
                "stream-json", "--verbose", "--tools", "", "--strict-mcp-config", "--setting-sources", "",
                "--no-session-persistence", "--disable-slash-commands", "--system-prompt-file", systemFile.toString()));
        if (o.jsonSchema() != null) cmd.addAll(List.of("--json-schema", windows ? windowsArg(o.jsonSchema()) : o.jsonSchema()));
        if (o.model() != null) cmd.addAll(List.of("--model", o.model()));
        if (o.effort() != null) cmd.addAll(List.of("--effort", o.effort()));
        return cmd;
    }

    /**
     * Windows rebuilds one command line from the arguments and the child splits it again (CommandLineToArgvW rules),
     * so a quote needs a backslash and the backslashes right before a quote are doubled. The JDK adds the outer
     * quotes itself when the argument has a space.
     */
    static String windowsArg(String arg) {
        StringBuilder sb = new StringBuilder();
        int backslashes = 0;
        for (int i = 0; i < arg.length(); i++) {
            char c = arg.charAt(i);
            if (c == '\\') {
                backslashes++;
                continue;
            }
            if (c == '"') {
                sb.append("\\".repeat(backslashes * 2 + 1)).append('"');
            } else {
                sb.append("\\".repeat(backslashes)).append(c);
            }
            backslashes = 0;
        }
        // Trailing backslashes stay as they are: the JDK handles the one before its closing quote. A JSON object
        // never ends with one.
        sb.append("\\".repeat(backslashes));
        return sb.toString();
    }
}
