package com.example.claudecodehelper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * How the {@code claude} children run: in a private work dir outside any project (so no CLAUDE.md loads), with every
 * {@code ANTHROPIC_*} variable and the cloud-billing switches removed (so Claude Code always uses the subscription
 * login, never an API key), and with their pids written to {@code claude-pids.txt} so a crashed run's leftovers
 * die on the next start.
 */
public final class ProcessEnv implements Launcher {
    private static final Logger LOG = Logger.getLogger("claude-code-helper");
    private static final long SELF = ProcessHandle.current().pid();

    /** Variables that would send Claude Code's calls to a cloud provider's billing instead of the subscription. */
    private static final List<String> OTHER_BILLING = List.of("CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CODE_USE_VERTEX",
            "CLAUDE_CODE_USE_FOUNDRY", "AWS_BEARER_TOKEN_BEDROCK");
    /** The server's own token variable: the child has no use for it. */
    public static final String TOKEN_ENV = "CLAUDE_HELPER_TOKEN";

    private final Path workDir;
    private final Path pidFile;

    public ProcessEnv(Path workDir) {
        this.workDir = workDir;
        this.pidFile = workDir.resolve("claude-pids.txt");
    }

    /** Removes every ANTHROPIC_ variable (API key, auth token, base URL...), the cloud switches and the helper token. */
    public static void scrub(Map<String, String> env) {
        env.keySet().removeIf(k -> {
            String key = k.toUpperCase(Locale.ROOT);
            return key.startsWith("ANTHROPIC_") || OTHER_BILLING.contains(key) || key.equals(TOKEN_ENV);
        });
    }

    /** A builder for {@code command} in the work dir with the scrubbed environment. */
    public ProcessBuilder builder(List<String> command) throws IOException {
        Files.createDirectories(workDir);
        ProcessBuilder pb = new ProcessBuilder(command).directory(workDir.toFile());
        scrub(pb.environment());
        return pb;
    }

    @Override
    public Proc start(List<String> command) throws IOException {
        Process process = builder(command).start();
        record(process.toHandle());
        return new Proc() {
            @Override
            public OutputStream stdin() {
                return process.getOutputStream();
            }

            @Override
            public InputStream stdout() {
                return process.getInputStream();
            }

            @Override
            public InputStream stderr() {
                return process.getErrorStream();
            }

            @Override
            public boolean alive() {
                return process.isAlive();
            }

            @Override
            public void kill() {
                killTree(process.toHandle());
                forget(process.pid());
            }
        };
    }

    /**
     * Kills children a previous run left behind: only pids whose owning process is gone and that are still the same
     * {@code claude} process (same start time), so a reused pid or another running helper is never touched.
     */
    public synchronized int killLeftovers() {
        List<String> keep = new ArrayList<>();
        int killed = 0;
        for (String line : lines()) {
            String[] f = line.trim().split(" ");
            if (f.length != 3) continue;
            try {
                long owner = Long.parseLong(f[0]), pid = Long.parseLong(f[1]), started = Long.parseLong(f[2]);
                if (owner != SELF && ProcessHandle.of(owner).map(ProcessHandle::isAlive).orElse(false)) {
                    keep.add(line);
                    continue;
                }
                Optional<ProcessHandle> h = ProcessHandle.of(pid);
                if (h.isPresent() && isClaude(h.get()) && startMillis(h.get()) == started) {
                    killTree(h.get());
                    killed++;
                }
            } catch (NumberFormatException ignored) {
                // a damaged line; drop it
            }
        }
        write(keep);
        if (killed > 0) LOG.info("stopped " + killed + " leftover Claude Code process(es) from an earlier run");
        return killed;
    }

    /** The children first: once the parent is gone they can no longer be found through it. */
    static void killTree(ProcessHandle h) {
        h.descendants().toList().forEach(ProcessHandle::destroyForcibly);
        h.destroyForcibly();
    }

    static boolean isClaude(ProcessHandle h) {
        String command = h.info().command().orElse("").replace('\\', '/').toLowerCase(Locale.ROOT);
        if (command.endsWith("/claude") || command.endsWith("/claude.exe") || command.equals("claude")
                || command.equals("claude.exe")) return true;
        // Linux and macOS resolve the symlink (.../claude/versions/<ver>) and npm installs run as node: match our flags.
        String line = h.info().commandLine().orElse("");
        return line.contains("--input-format stream-json") && line.contains("--system-prompt-file");
    }

    private synchronized void record(ProcessHandle h) {
        List<String> all = lines();
        all.add(SELF + " " + h.pid() + " " + startMillis(h));
        write(all);
    }

    private synchronized void forget(long pid) {
        List<String> all = lines();
        all.removeIf(l -> {
            String[] f = l.trim().split(" ");
            return f.length == 3 && f[1].equals(Long.toString(pid));
        });
        write(all);
    }

    private static long startMillis(ProcessHandle h) {
        return h.info().startInstant().map(Instant::toEpochMilli).orElse(0L);
    }

    private List<String> lines() {
        try {
            return Files.exists(pidFile) ? new ArrayList<>(Files.readAllLines(pidFile, StandardCharsets.UTF_8)) : new ArrayList<>();
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    private void write(List<String> lines) {
        try {
            Files.createDirectories(workDir);
            Files.write(pidFile, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warning("couldn't update the pid file: " + e.getMessage());
        }
    }
}
