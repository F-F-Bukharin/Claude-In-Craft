package com.example.claudecodehelper.server;

import com.example.claudecodehelper.Json;
import com.example.claudecodehelper.ProcessEnv;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * What {@code /health} says about Claude Code: the path found, its version, and whether it is signed in, from
 * {@code claude --version} and {@code claude auth status} (no model call, no usage). Cached for a minute. The
 * account email is left out on purpose.
 */
final class ClaudeStatus implements Supplier<JsonObject> {
    private static final long CACHE_MILLIS = 60_000;

    private final Supplier<String> executable;
    private final ProcessEnv env;
    private JsonObject cached;
    private long cachedAt;

    ClaudeStatus(Supplier<String> executable, Path workDir) {
        this.executable = executable;
        this.env = new ProcessEnv(workDir);
    }

    @Override
    public synchronized JsonObject get() {
        long now = System.currentTimeMillis();
        if (cached == null || now - cachedAt > CACHE_MILLIS) {
            cached = check();
            cachedAt = now;
        }
        return cached.deepCopy();
    }

    private JsonObject check() {
        JsonObject o = new JsonObject();
        String exe = executable.get();
        o.addProperty("found", exe != null);
        if (exe == null) {
            o.addProperty("hint", "install Claude Code, or start the helper with --claude <path>");
            return o;
        }
        o.addProperty("path", exe);
        String version = run(List.of(exe, "--version"));
        if (version != null) o.addProperty("version", version.strip().lines().findFirst().orElse(""));
        String auth = run(List.of(exe, "auth", "status"));
        try {
            JsonObject a = JsonParser.parseString(auth == null ? "" : auth.strip()).getAsJsonObject();
            o.addProperty("signed_in", Json.bool(a, "loggedIn"));
            if (!Json.str(a, "authMethod").isEmpty()) o.addProperty("auth_method", Json.str(a, "authMethod"));
            if (!Json.str(a, "subscriptionType").isEmpty()) o.addProperty("subscription", Json.str(a, "subscriptionType"));
        } catch (RuntimeException e) {
            o.add("signed_in", null); // unknown: this Claude Code has no JSON auth status
        }
        return o;
    }

    /** stdout of a short command, or null. Both streams go to files so the timeout holds. */
    private String run(List<String> command) {
        Path out = null;
        try {
            out = Files.createTempFile("claude-status", ".txt");
            Process p = env.builder(command).redirectOutput(out.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectInput(ProcessBuilder.Redirect.PIPE).start();
            p.getOutputStream().close();
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return Files.readString(out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (out != null) {
                try {
                    Files.deleteIfExists(out);
                } catch (IOException ignored) {
                    // a temp file
                }
            }
        }
    }
}
