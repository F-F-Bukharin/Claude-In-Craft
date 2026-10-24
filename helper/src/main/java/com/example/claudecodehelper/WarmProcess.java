package com.example.claudecodehelper;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * One warm {@code claude} process on stream-json. {@link #state}, {@link #seen}, {@link #lastUsed} and
 * {@link #dedicated} belong to {@link ClaudeCode} and change only under its lock; this class owns the pipes.
 *
 * <p>Designed against the mod's known bridge bugs: the turn's stdin write runs on a writer thread inside the turn's
 * deadline (a child that never reads stdin can't hang the caller); {@link #kill} only flags and hands the tree walk
 * to the reaper thread (no process walk or pid-file I/O on the caller's thread); a kill that lands while the process
 * is still starting is applied the moment it has started.
 */
final class WarmProcess {
    private static final Logger LOG = Logger.getLogger("claude-code-helper");
    @SuppressWarnings("StringOperationCanBeSimplified")
    private static final String EOF = new String("eof"); // markers compared by identity: no stdout line is one of these
    @SuppressWarnings("StringOperationCanBeSimplified")
    private static final String KILLED = new String("killed");
    @SuppressWarnings("StringOperationCanBeSimplified")
    private static final String OVERSIZE = new String("oversize");
    @SuppressWarnings("StringOperationCanBeSimplified")
    private static final String WRITE_FAILED = new String("write-failed");
    private static volatile boolean warnedKeySource;

    enum State { STARTING, BUSY, IDLE, DEAD }

    /** What a turn gave back: the result event, plus what arrived before it. */
    record Turn(JsonObject result, JsonObject rateLimit, String model) {}

    final List<String> command;
    final boolean schema;
    // guarded by ClaudeCode's lock
    State state = State.STARTING;
    boolean dedicated;
    JsonArray seen = new JsonArray();
    long lastUsed;

    private final int maxLine;
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private final StringBuilder err = new StringBuilder();
    private Launcher.Proc proc; // guarded by this
    private boolean killed; // guarded by this
    private Executor reaper; // guarded by this
    private Thread errPump;
    private volatile String model = "";

    WarmProcess(List<String> command, boolean schema, boolean dedicated, long now, int maxLine) {
        this.command = command;
        this.schema = schema;
        this.dedicated = dedicated;
        this.lastUsed = now;
        this.maxLine = maxLine;
    }

    /** Hands over the started process. If a kill came first, the process dies now. */
    void attach(Launcher.Proc p, Executor reaperPool) {
        boolean lateKill;
        synchronized (this) {
            proc = p;
            reaper = reaperPool;
            lateKill = killed;
        }
        if (lateKill) {
            reap(reaperPool, p);
            return;
        }
        Thread out = new Thread(this::pumpOut, "claude-code-out");
        out.setDaemon(true);
        out.start();
        errPump = new Thread(this::pumpErr, "claude-code-err");
        errPump.setDaemon(true);
        errPump.start();
    }

    synchronized boolean alive() {
        return proc != null && !killed && proc.alive();
    }

    /** Stops the process: idempotent, safe from any thread, and never walks the process tree on the caller's thread. */
    void kill(Executor reaperPool) {
        Launcher.Proc p;
        synchronized (this) {
            if (killed) return;
            killed = true;
            p = proc;
            if (reaper == null) reaper = reaperPool;
        }
        lines.add(KILLED); // an in-flight turn ends at once
        if (p != null) reap(reaperPool, p);
    }

    private static void reap(Executor reaperPool, Launcher.Proc p) {
        try {
            reaperPool.execute(p::kill);
        } catch (RejectedExecutionException e) {
            p.kill(); // shutting down: no reaper left
        }
    }

    /**
     * Writes one stream-json line and reads events until the turn's {@code result}. {@code afterWrite} runs once the
     * write is under way (the caller starts the next spare there). The deadline covers the write too.
     */
    Turn turn(String line, long timeoutMillis, Executor writers, Runnable afterWrite) throws ClaudeCodeException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        Launcher.Proc p;
        synchronized (this) {
            p = proc;
            if (killed || p == null) throw new ClaudeCodeException(ClaudeCodeException.Kind.CANCELLED, "the session was stopped");
        }
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        try {
            writers.execute(() -> {
                try {
                    OutputStream in = p.stdin();
                    in.write(bytes);
                    in.flush();
                } catch (IOException e) {
                    lines.add(WRITE_FAILED);
                }
            });
        } catch (RejectedExecutionException e) {
            throw new ClaudeCodeException(ClaudeCodeException.Kind.CANCELLED, "shutting down");
        }
        if (afterWrite != null) afterWrite.run();
        JsonObject rateLimit = null;
        try {
            while (true) {
                long left = deadline - System.nanoTime();
                String l = left <= 0 ? null : lines.poll(left, TimeUnit.NANOSECONDS);
                if (l == null) {
                    throw new ClaudeCodeException(ClaudeCodeException.Kind.TIMEOUT,
                            "no answer within " + (timeoutMillis / 1000) + " s");
                }
                if (l == KILLED) throw new ClaudeCodeException(ClaudeCodeException.Kind.CANCELLED, "the session was stopped");
                if (l == OVERSIZE) {
                    throw new ClaudeCodeException(ClaudeCodeException.Kind.BAD_OUTPUT, "a Claude Code output line was over "
                            + (maxLine / (1024 * 1024)) + " MB");
                }
                if (l == EOF || l == WRITE_FAILED) throw exited();
                JsonObject event = parse(l);
                if (event == null) continue;
                switch (Json.str(event, "type")) {
                    case "result" -> {
                        return new Turn(event, rateLimit, model);
                    }
                    case "rate_limit_event" -> rateLimit = Json.obj(event, "rate_limit_info");
                    case "system" -> {
                        if (Json.str(event, "subtype").equals("init")) init(event);
                    }
                    case "assistant" -> {
                        String m = Json.str(Json.obj(event, "message"), "model");
                        if (!m.isEmpty()) model = m;
                    }
                    default -> { } // post_turn_summary, stream events and the rest
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClaudeCodeException(ClaudeCodeException.Kind.CANCELLED, "interrupted");
        }
    }

    private void init(JsonObject event) {
        if (!Json.str(event, "model").isEmpty()) model = Json.str(event, "model");
        String source = Json.str(event, "apiKeySource");
        if (!source.isEmpty() && !source.equals("none") && !warnedKeySource) {
            warnedKeySource = true;
            LOG.warning("Claude Code reports apiKeySource=" + source + ": calls may bill an API key instead of the subscription");
        }
    }

    /** The process ended before the result: the first stderr line says why. */
    private ClaudeCodeException exited() throws InterruptedException {
        if (errPump != null) errPump.join(1000); // the exit message may still be on its way
        String first;
        synchronized (err) {
            first = err.toString().lines().map(String::strip).filter(s -> !s.isEmpty()).findFirst().orElse("");
        }
        if (first.isEmpty()) return new ClaudeCodeException(ClaudeCodeException.Kind.CLI, "Claude Code exited before answering");
        String lower = first.toLowerCase(Locale.ROOT);
        boolean login = lower.contains("not logged in") || lower.contains("/login") || lower.contains("please log in");
        return new ClaudeCodeException(login ? ClaudeCodeException.Kind.NOT_SIGNED_IN : ClaudeCodeException.Kind.CLI,
                Json.clip(first));
    }

    private void pumpOut() {
        try (Reader r = reader(procOrNull())) {
            if (r == null) return;
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            for (int n; (n = r.read(buf)) >= 0; ) {
                for (int i = 0; i < n; i++) {
                    char c = buf[i];
                    if (c == '\n') {
                        int end = sb.length() > 0 && sb.charAt(sb.length() - 1) == '\r' ? sb.length() - 1 : sb.length();
                        lines.add(sb.substring(0, end));
                        sb.setLength(0);
                    } else if (sb.length() >= maxLine) {
                        lines.add(OVERSIZE); // the turn kills the process; stop buffering
                        return;
                    } else {
                        sb.append(c);
                    }
                }
            }
        } catch (IOException ignored) {
            // killed; EOF below
        } finally {
            lines.add(EOF);
        }
    }

    private void pumpErr() {
        Launcher.Proc p = procOrNull();
        if (p == null) return;
        try (Reader r = new BufferedReader(new InputStreamReader(p.stderr(), StandardCharsets.UTF_8))) {
            char[] buf = new char[1024];
            for (int n; (n = r.read(buf)) >= 0; ) {
                synchronized (err) {
                    if (err.length() < 4096) err.append(buf, 0, Math.min(n, 4096 - err.length()));
                }
            }
        } catch (IOException ignored) {
            // killed
        }
    }

    private synchronized Launcher.Proc procOrNull() {
        return proc;
    }

    private static Reader reader(Launcher.Proc p) {
        return p == null ? null : new BufferedReader(new InputStreamReader(p.stdout(), StandardCharsets.UTF_8));
    }

    private static JsonObject parse(String line) {
        try {
            return JsonParser.parseString(line).getAsJsonObject();
        } catch (RuntimeException e) {
            return null; // not an event; ignore it
        }
    }
}
