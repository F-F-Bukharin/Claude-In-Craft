package com.example.claudeplayer.core.brain;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * How the Claude Code children run: in the work dir, with every {@code ANTHROPIC_*} variable removed (a key set for
 * {@code brain = "api"} would otherwise make Claude Code bill the API instead of the subscription), and with their
 * pids written to {@code claude-pids.txt} so a crashed game's leftovers die on the next start.
 */
public final class ClaudeCodeEnv implements ClaudeCodeSessions.Launcher {
    private static final Logger LOG = LoggerFactory.getLogger(ClaudeCodeEnv.class);
    private static final long SELF = ProcessHandle.current().pid();

    private final Path workDir;
    private final Path pidFile;

    public ClaudeCodeEnv(Path workDir) {
        this.workDir = workDir;
        this.pidFile = workDir.resolve("claude-pids.txt");
    }

    /** Variables that would send Claude Code's calls to a cloud provider's billing instead of the subscription. */
    private static final List<String> OTHER_BILLING = List.of("CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CODE_USE_VERTEX",
            "CLAUDE_CODE_USE_FOUNDRY", "AWS_BEARER_TOKEN_BEDROCK");

    /** Removes every ANTHROPIC_ variable (API key, auth token, base URL...) and the cloud-provider switches. */
    static void scrub(Map<String, String> env) {
        env.keySet().removeIf(k -> {
            String key = k.toUpperCase(Locale.ROOT);
            return key.startsWith("ANTHROPIC_") || OTHER_BILLING.contains(key);
        });
    }

    ProcessBuilder builder(List<String> command) throws IOException {
        Files.createDirectories(workDir);
        ProcessBuilder pb = new ProcessBuilder(command).directory(workDir.toFile());
        scrub(pb.environment());
        return pb;
    }

    @Override
    public ClaudeCodeSessions.Proc start(List<String> command) throws IOException {
        Process process = builder(command).start();
        record(process.toHandle());
        return new ClaudeCodeSessions.Proc() {
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
     * Kills children a previous run left behind: only pids whose owning game process is gone and that are still the
     * same {@code claude} process (same start time), so a reused pid or another running game is never touched.
     */
    public synchronized void killLeftovers() {
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
        if (killed > 0) LOG.info("stopped {} leftover Claude Code process(es) from an earlier run", killed);
    }

    /** The children first: once the parent is gone they can no longer be found through it. */
    static void killTree(ProcessHandle h) {
        h.descendants().toList().forEach(ProcessHandle::destroyForcibly);
        h.destroyForcibly();
    }

    static boolean isClaude(ProcessHandle h) {
        String command = h.info().command().orElse("").replace('\\', '/').toLowerCase(Locale.ROOT);
        return command.endsWith("/claude") || command.endsWith("/claude.exe") || command.equals("claude")
                || command.equals("claude.exe");
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
            LOG.warn("couldn't update {}: {}", pidFile, e.getMessage());
        }
    }
}
