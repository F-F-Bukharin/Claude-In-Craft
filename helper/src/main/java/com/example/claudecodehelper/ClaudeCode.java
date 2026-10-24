package com.example.claudecodehelper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/**
 * Your own Claude Code login as a library: warm {@code claude} processes you send turns to, locked down so Claude
 * Code is only a model (no tools, no settings, no MCP, a replaced system prompt).
 *
 * <pre>{@code
 * try (ClaudeCode cc = ClaudeCode.builder().model("claude-haiku-5-5").systemPrompt("Be brief.").build();
 *      ClaudeCode.Session chat = cc.open()) {
 *     System.out.println(chat.send("Name three colours.").text());
 *     System.out.println(chat.send("And three more.").text()); // same warm process, prompt cache hit
 * }
 * }</pre>
 *
 * <p>Two ways in. {@link #open()} gives a {@link Session}: one conversation on one process, for code that keeps it.
 * {@link #lease} is for stateless callers such as an HTTP server: it hands over an idle session whose history the new
 * request extends, or a fresh one (taken from the spare that is kept started ahead), and the caller sends only what
 * the session hasn't read.
 *
 * <p>At most {@code maxSessions} sessions live at once (idle ones are evicted, oldest first, to make room; past that a
 * request fails with {@link ClaudeCodeException.Kind#BUSY}). Sessions unused for {@code idleMinutes} are stopped.
 * Every state change happens under one lock, so the idle sweep never takes a session that a turn is starting on.
 */
public final class ClaudeCode implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger("claude-code-helper");

    private final Options defaults;
    private final String configuredPath;
    private final Path workDir;
    private final Launcher launcher;
    private final boolean realLauncher;
    private final long timeoutMillis;
    private final long idleMillis;
    private final int maxSessions;
    private final int maxLine;
    private final boolean spareEnabled;
    private final LongSupplier clock;
    private final ExecutorService writers = Executors.newCachedThreadPool(daemon("claude-code-write"));
    private final ExecutorService reaper = Executors.newSingleThreadExecutor(daemon("claude-code-reaper"));
    private final ScheduledExecutorService sweeper;
    private final Thread shutdownHook;

    private final Object lock = new Object();
    private final List<WarmProcess> sessions = new ArrayList<>(); // guarded by lock
    private WarmProcess spare; // guarded by lock
    private boolean closed; // guarded by lock
    private volatile JsonObject lastRateLimit;

    private ClaudeCode(Builder b) {
        this.defaults = new Options(b.model, b.effort, b.systemPrompt, b.jsonSchema);
        this.configuredPath = b.claudePath;
        this.workDir = b.workDir != null ? b.workDir
                : Path.of(System.getProperty("java.io.tmpdir"), "claude-code-helper");
        this.realLauncher = b.launcher == null;
        ProcessEnv env = realLauncher ? new ProcessEnv(workDir) : null;
        this.launcher = realLauncher ? env : b.launcher;
        this.timeoutMillis = b.turnTimeout.toMillis();
        this.idleMillis = b.idleMinutes * 60_000L;
        this.maxSessions = b.maxSessions;
        this.maxLine = b.maxLineBytes;
        this.spareEnabled = b.spare;
        this.clock = b.clock != null ? b.clock : System::currentTimeMillis;
        if (env != null) env.killLeftovers();
        boolean sweep = b.backgroundSweep != null ? b.backgroundSweep : realLauncher;
        if (sweep && idleMillis > 0) {
            sweeper = Executors.newSingleThreadScheduledExecutor(daemon("claude-code-idle"));
            long every = Math.max(1_000, Math.min(idleMillis / 2, 30_000));
            sweeper.scheduleWithFixedDelay(this::sweepIdle, every, every, TimeUnit.MILLISECONDS);
        } else {
            sweeper = null;
        }
        if (realLauncher) {
            shutdownHook = new Thread(this::close, "claude-code-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        } else {
            shutdownHook = null;
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The builder's model, effort, system prompt and schema. Derive per-call options from it with the withers. */
    public Options defaults() {
        return defaults;
    }

    /** The {@code claude} executable that will run, or null when none is found. */
    public String executable() {
        return Commands.find(configuredPath);
    }

    /** The latest {@code rate_limit_info} any session reported (Claude Code sends it on a session's first turn), or null. */
    public JsonObject lastRateLimit() {
        return lastRateLimit;
    }

    public int maxSessions() {
        return maxSessions;
    }

    /** Sessions alive now (starting, busy or idle), not counting the spare. */
    public int liveSessions() {
        synchronized (lock) {
            return sessions.size();
        }
    }

    /** A conversation on its own warm process, with the default options. */
    public Session open() throws ClaudeCodeException {
        return open(defaults);
    }

    public Session open(Options options) throws ClaudeCodeException {
        List<String> cmd = command(options);
        Taken t = take(cmd, options.jsonSchema() != null, null, true);
        synchronized (lock) {
            if (t.wp.state == WarmProcess.State.BUSY) {
                t.wp.state = WarmProcess.State.IDLE;
                t.wp.lastUsed = clock.getAsLong();
            }
        }
        return new Session(t.wp, cmd);
    }

    /**
     * A session for {@code history} (Messages-style {role, content} objects, oldest first): an idle one started with
     * the same options whose history this one extends, else a fresh one. Send {@code history} from
     * {@link Lease#from()} on, then {@link Lease#keep} the history with the reply appended, or close the lease to
     * discard the session.
     */
    public Lease lease(Options options, JsonArray history) throws ClaudeCodeException {
        List<String> cmd = command(options);
        Taken t = take(cmd, options.jsonSchema() != null, Messages.canonical(history), false);
        return new Lease(t.wp, t.from, cmd);
    }

    /** Starts the spare for {@code options} ahead of the first call, so even that one skips Claude Code's startup. */
    public void prewarm(Options options) {
        try {
            refill(command(options), options.jsonSchema() != null);
        } catch (ClaudeCodeException e) {
            LOG.info("no spare started: " + e.getMessage());
        }
    }

    /** Stops sessions and the spare unused for the idle time. A session mid-turn is never idle. Runs on a timer too. */
    public void sweepIdle() {
        long now = clock.getAsLong();
        List<WarmProcess> idle = new ArrayList<>();
        synchronized (lock) {
            if (spare != null && spare.state == WarmProcess.State.IDLE && now - spare.lastUsed >= idleMillis) {
                spare.state = WarmProcess.State.DEAD;
                idle.add(spare);
                spare = null;
            }
            for (WarmProcess wp : List.copyOf(sessions)) {
                if (wp.state == WarmProcess.State.IDLE && now - wp.lastUsed >= idleMillis) {
                    wp.state = WarmProcess.State.DEAD;
                    sessions.remove(wp);
                    idle.add(wp);
                }
            }
        }
        idle.forEach(this::kill);
        if (!idle.isEmpty()) LOG.info("stopped " + idle.size() + " idle Claude Code process(es)");
    }

    /** Stops every process, including one still starting, and waits briefly for the kills. */
    @Override
    public void close() {
        List<WarmProcess> all;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            all = new ArrayList<>(sessions);
            if (spare != null) all.add(spare);
            sessions.clear();
            spare = null;
            all.forEach(wp -> wp.state = WarmProcess.State.DEAD);
        }
        if (sweeper != null) sweeper.shutdownNow();
        all.forEach(this::kill);
        reaper.shutdown();
        try {
            reaper.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        writers.shutdownNow();
        if (shutdownHook != null && Thread.currentThread() != shutdownHook) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // already shutting down
            }
        }
    }

    private record Taken(WarmProcess wp, int from) {}

    /** Continues an idle session, takes the spare, or starts a process. The result is BUSY and counted. */
    private Taken take(List<String> cmd, boolean schema, JsonArray history, boolean dedicated) throws ClaudeCodeException {
        WarmProcess fresh;
        List<WarmProcess> evicted = new ArrayList<>();
        synchronized (lock) {
            if (closed) throw new ClaudeCodeException(ClaudeCodeException.Kind.CANCELLED, "shutting down");
            if (history != null) {
                WarmProcess best = null;
                int bestFrom = -1;
                for (WarmProcess wp : sessions) {
                    if (wp.state != WarmProcess.State.IDLE || wp.dedicated || !wp.command.equals(cmd)) continue;
                    int from = Messages.continues(wp.seen, history);
                    if (from > bestFrom) {
                        best = wp;
                        bestFrom = from;
                    }
                }
                if (best != null) {
                    best.state = WarmProcess.State.BUSY;
                    best.lastUsed = clock.getAsLong();
                    return new Taken(best, bestFrom);
                }
            }
            if (sessions.size() >= maxSessions) {
                WarmProcess lru = null;
                for (WarmProcess wp : sessions) {
                    if (wp.state == WarmProcess.State.IDLE && !wp.dedicated && (lru == null || wp.lastUsed < lru.lastUsed)) lru = wp;
                }
                if (lru == null) {
                    throw new ClaudeCodeException(ClaudeCodeException.Kind.BUSY,
                            "all " + maxSessions + " Claude Code sessions are busy; retry shortly");
                }
                lru.state = WarmProcess.State.DEAD;
                sessions.remove(lru);
                evicted.add(lru);
            }
            if (spare != null && spare.state == WarmProcess.State.IDLE && spare.command.equals(cmd) && spare.alive()) {
                fresh = spare;
                spare = null;
                fresh.state = WarmProcess.State.BUSY;
                fresh.dedicated = dedicated;
                fresh.lastUsed = clock.getAsLong();
                sessions.add(fresh);
            } else {
                fresh = new WarmProcess(cmd, schema, dedicated, clock.getAsLong(), maxLine);
                sessions.add(fresh); // counts against the cap while it starts
            }
        }
        evicted.forEach(this::kill);
        if (fresh.state == WarmProcess.State.STARTING) start(fresh);
        return new Taken(fresh, 0);
    }

    /** Starts {@code wp}'s process outside the lock; a close or kill that came meanwhile wins. */
    private void start(WarmProcess wp) throws ClaudeCodeException {
        Launcher.Proc proc;
        try {
            proc = launcher.start(wp.command);
        } catch (IOException e) {
            synchronized (lock) {
                sessions.remove(wp);
                wp.state = WarmProcess.State.DEAD;
            }
            throw new ClaudeCodeException(ClaudeCodeException.Kind.NO_CLI, "couldn't start claude: " + e.getMessage());
        }
        wp.attach(proc, reaper);
        boolean cancelled;
        synchronized (lock) {
            cancelled = closed || wp.state == WarmProcess.State.DEAD;
            if (!cancelled) wp.state = WarmProcess.State.BUSY;
            else sessions.remove(wp);
        }
        if (cancelled) {
            kill(wp);
            throw new ClaudeCodeException(ClaudeCodeException.Kind.CANCELLED, "stopped while starting");
        }
    }

    /** Makes sure an idle spare for {@code cmd} is running. A failure only costs the next fresh session its head start. */
    private void refill(List<String> cmd, boolean schema) {
        WarmProcess stale;
        WarmProcess mine;
        synchronized (lock) {
            if (closed || !spareEnabled) return;
            if (spare != null && spare.command.equals(cmd) && (spare.state == WarmProcess.State.STARTING || spare.alive())) return;
            stale = spare;
            mine = new WarmProcess(cmd, schema, false, clock.getAsLong(), maxLine);
            spare = mine;
        }
        if (stale != null) kill(stale);
        Launcher.Proc proc;
        try {
            proc = launcher.start(cmd);
        } catch (IOException e) {
            synchronized (lock) {
                if (spare == mine) spare = null;
            }
            LOG.warning("couldn't start a spare Claude Code: " + e.getMessage());
            return;
        }
        mine.attach(proc, reaper);
        boolean keep;
        synchronized (lock) {
            keep = !closed && spare == mine && mine.state == WarmProcess.State.STARTING;
            if (keep) {
                mine.state = WarmProcess.State.IDLE;
                mine.lastUsed = clock.getAsLong();
            }
        }
        if (!keep) kill(mine);
    }

    private void discard(WarmProcess wp) {
        synchronized (lock) {
            sessions.remove(wp);
            wp.state = WarmProcess.State.DEAD;
        }
        kill(wp);
    }

    private void kill(WarmProcess wp) {
        wp.kill(reaper);
    }

    /** One turn on a BUSY session; any failure discards it. */
    private Reply turn(WarmProcess wp, List<String> cmd, JsonArray blocks, boolean refillSpare) throws ClaudeCodeException {
        long started = System.nanoTime();
        WarmProcess.Turn t;
        try {
            t = wp.turn(userLine(blocks), timeoutMillis, writers, refillSpare ? () -> refill(cmd, wp.schema) : null);
        } catch (ClaudeCodeException e) {
            discard(wp);
            throw e;
        }
        Reply reply;
        try {
            reply = Results.reply(t.result(), t.rateLimit(), t.model(), wp.schema, (System.nanoTime() - started) / 1_000_000);
        } catch (ClaudeCodeException e) {
            discard(wp); // an error turn leaves the session in an unknown state
            throw e;
        }
        if (reply.rateLimit() != null) lastRateLimit = reply.rateLimit();
        return reply;
    }

    /** Text and image blocks only; throws IllegalArgumentException before anything is sent. */
    private static void validate(JsonArray blocks) {
        if (blocks.isEmpty()) throw new IllegalArgumentException("a turn needs at least one block");
        for (JsonElement b : blocks) {
            if (!b.isJsonObject()) throw new IllegalArgumentException("every block must be an object");
            Blocks.check(b.getAsJsonObject());
        }
    }

    /** One stream-json user line carrying {@code blocks}. */
    static String userLine(JsonArray blocks) {
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.add("content", blocks);
        JsonObject line = new JsonObject();
        line.addProperty("type", "user");
        line.add("message", message);
        return line.toString();
    }

    /**
     * The command for {@code o}. The system prompt goes to a file named by its hash, so a changed prompt is a changed
     * command (the spare is replaced) and a file a running process reads is never rewritten.
     */
    List<String> command(Options o) throws ClaudeCodeException {
        String exe = executable();
        if (exe == null) {
            if (realLauncher) {
                throw new ClaudeCodeException(ClaudeCodeException.Kind.NO_CLI,
                        "claude not found: install Claude Code, or pass its path");
            }
            exe = "claude";
        }
        String name = "system-" + Json.sha256(o.systemPrompt()).substring(0, 16) + ".md";
        Path file = workDir.resolve(name);
        if (!Files.isRegularFile(file)) {
            try {
                Files.createDirectories(workDir);
                Path tmp = Files.createTempFile(workDir, "system-", ".tmp");
                Files.writeString(tmp, o.systemPrompt(), StandardCharsets.UTF_8);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                throw new ClaudeCodeException(ClaudeCodeException.Kind.CLI, "couldn't write the system prompt file: " + e.getMessage());
            }
        }
        return Commands.stream(exe, o, file, Commands.WINDOWS);
    }

    private static ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    /** One conversation on one warm process. One turn at a time; {@link #cancel} works from any thread. */
    public final class Session implements AutoCloseable {
        private final WarmProcess wp;
        private final List<String> cmd;
        private boolean first = true;

        private Session(WarmProcess wp, List<String> cmd) {
            this.wp = wp;
            this.cmd = cmd;
        }

        public Reply send(String text) throws ClaudeCodeException {
            return send(List.of(Blocks.text(text)));
        }

        /** Text and image blocks (see {@link Blocks}), sent as one user turn. */
        public Reply send(List<JsonObject> blocks) throws ClaudeCodeException {
            JsonArray content = new JsonArray();
            blocks.forEach(content::add);
            validate(content);
            synchronized (lock) {
                if (wp.state == WarmProcess.State.DEAD) {
                    throw new ClaudeCodeException(ClaudeCodeException.Kind.CANCELLED, "the session has ended");
                }
                if (wp.state != WarmProcess.State.IDLE) throw new IllegalStateException("one turn at a time");
                wp.state = WarmProcess.State.BUSY;
                wp.lastUsed = clock.getAsLong();
            }
            boolean refillSpare = first;
            first = false;
            Reply r = turn(wp, cmd, content, refillSpare);
            synchronized (lock) {
                if (wp.state == WarmProcess.State.BUSY) {
                    wp.state = WarmProcess.State.IDLE;
                    wp.lastUsed = clock.getAsLong();
                }
            }
            return r;
        }

        /** Stops the process; a turn in flight ends with CANCELLED. */
        public void cancel() {
            discard(wp);
        }

        public boolean alive() {
            synchronized (lock) {
                return wp.state != WarmProcess.State.DEAD;
            }
        }

        @Override
        public void close() {
            discard(wp);
        }
    }

    /** A session handed to a stateless caller for one turn. */
    public final class Lease implements AutoCloseable {
        private final WarmProcess wp;
        private final int from;
        private final List<String> cmd;
        private boolean kept;

        private Lease(WarmProcess wp, int from, List<String> cmd) {
            this.wp = wp;
            this.from = from;
            this.cmd = cmd;
        }

        /** The index of the first history message the session hasn't read: 0 for a fresh session. */
        public int from() {
            return from;
        }

        /** True when this continues a warm conversation. */
        public boolean reused() {
            return from > 0;
        }

        /** Sends the not-yet-read part of the history, already rendered as text and image blocks. */
        public Reply send(JsonArray blocks) throws ClaudeCodeException {
            validate(blocks);
            return turn(wp, cmd, blocks, from == 0);
        }

        /** Returns the session to the pool, remembering {@code seen}: the history plus the reply as it will be echoed. */
        public void keep(JsonArray seen) {
            JsonArray canon = Messages.canonical(seen);
            synchronized (lock) {
                if (wp.state != WarmProcess.State.BUSY) return;
                wp.seen = canon;
                wp.state = WarmProcess.State.IDLE;
                wp.lastUsed = clock.getAsLong();
                kept = true;
            }
        }

        public void cancel() {
            discard(wp);
        }

        /** Without {@link #keep}, the session is stopped. */
        @Override
        public void close() {
            boolean discard;
            synchronized (lock) {
                discard = !kept;
            }
            if (discard) discard(wp);
        }
    }

    public static final class Builder {
        private String claudePath = "";
        private String model;
        private String effort;
        private String systemPrompt;
        private String jsonSchema;
        private Duration turnTimeout = Duration.ofMinutes(5);
        private int idleMinutes = 10;
        private int maxSessions = 4;
        private int maxLineBytes = 8 * 1024 * 1024;
        private boolean spare = true;
        private Path workDir;
        private Launcher launcher;
        private LongSupplier clock;
        private Boolean backgroundSweep;

        private Builder() {}

        /** The claude executable; empty searches PATH, then ~/.local/bin. */
        public Builder claudePath(String path) {
            this.claudePath = path == null ? "" : path;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder effort(String effort) {
            this.effort = effort;
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        /** A JSON schema (an object) for structured output; replies then carry {@link Reply#structured()}. */
        public Builder jsonSchema(String jsonSchema) {
            this.jsonSchema = jsonSchema;
            return this;
        }

        /** How long one turn may take, the write of the turn included. Default 5 minutes. */
        public Builder turnTimeout(Duration timeout) {
            this.turnTimeout = timeout;
            return this;
        }

        /** Sessions and the spare unused this long are stopped. Default 10. */
        public Builder idleMinutes(int minutes) {
            this.idleMinutes = minutes;
            return this;
        }

        /** Live sessions at once, the spare not counted. Default 4. */
        public Builder maxSessions(int max) {
            if (max < 1) throw new IllegalArgumentException("maxSessions must be at least 1");
            this.maxSessions = max;
            return this;
        }

        /** Keep one idle process started ahead, so a fresh session skips startup. Default on. */
        public Builder spare(boolean spare) {
            this.spare = spare;
            return this;
        }

        /** Where the processes run and the prompt files live; must be outside any project. Default: a temp folder. */
        public Builder workDir(Path dir) {
            this.workDir = dir;
            return this;
        }

        /** Tests and custom process runners. A custom launcher skips the leftover sweep and the shutdown hook. */
        public Builder launcher(Launcher launcher) {
            this.launcher = launcher;
            return this;
        }

        Builder maxLineBytes(int bytes) {
            this.maxLineBytes = bytes;
            return this;
        }

        /** Tests: a fake clock. */
        public Builder clock(LongSupplier clock) {
            this.clock = clock;
            return this;
        }

        /** Whether a timer runs the idle sweep. Default: on with the real launcher, off with a custom one. */
        public Builder backgroundSweep(boolean on) {
            this.backgroundSweep = on;
            return this;
        }

        public ClaudeCode build() {
            return new ClaudeCode(this);
        }
    }
}
