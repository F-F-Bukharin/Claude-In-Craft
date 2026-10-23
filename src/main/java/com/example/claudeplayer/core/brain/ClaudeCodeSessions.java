package com.example.claudeplayer.core.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The warm {@code claude} processes behind {@link ClaudeCodeBrain}: the current order's session, and one spare
 * started ahead and left idle (it does nothing until its first stdin line), so the next order skips the CLI's
 * startup. A spare whose command no longer matches (new system prompt or flags) is replaced; a process unused for
 * {@code idleMillis} is stopped.
 *
 * <p>Threading: {@link #take}, {@link #refill} and the session's turn run on the brain thread; {@link #endCurrent}
 * may come from the server thread. The lock only guards the two fields: process starts, reads and writes happen
 * outside it, so the server thread never waits on process I/O.
 */
final class ClaudeCodeSessions {
    private static final Logger LOG = LoggerFactory.getLogger(ClaudeCodeSessions.class);
    @SuppressWarnings("StringOperationCanBeSimplified")
    private static final String EOF = new String("eof"); // compared by identity: no stdout line is this object

    /** Starts one process. The real one is {@link ClaudeCodeEnv}; tests script a fake. */
    interface Launcher {
        Proc start(List<String> command) throws IOException;
    }

    interface Proc {
        OutputStream stdin();

        InputStream stdout();

        InputStream stderr();

        boolean alive();

        /** Kills the process and its descendants. Never blocks on I/O. */
        void kill();
    }

    /** One warm process. {@code seen} is the transcript prefix it has already read; brain thread only. */
    final class Session {
        final Proc proc;
        final List<String> command;
        JsonArray seen = new JsonArray();
        private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        private final StringBuilder err = new StringBuilder();
        private Thread errPump;
        private volatile long lastUsed;
        private volatile boolean busy;

        private Session(Proc proc, List<String> command) {
            this.proc = proc;
            this.command = command;
            this.lastUsed = clock.getAsLong();
        }

        /** Sends one stream-json line. */
        void write(String line) throws BrainException {
            busy = true;
            lastUsed = clock.getAsLong();
            try {
                OutputStream in = proc.stdin();
                in.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                in.flush();
            } catch (IOException e) {
                busy = false;
                end(this);
                throw new BrainException(BrainException.Kind.CLI, "couldn't write the prompt: " + e.getMessage());
            }
        }

        /** Reads events until the turn's {@code result} event and returns it; any failure kills the session. */
        String await(long timeoutMillis) throws BrainException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            try {
                while (true) {
                    String line = lines.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                    if (line == null) {
                        end(this);
                        throw new BrainException(BrainException.Kind.TIMEOUT, "timed out");
                    }
                    if (line == EOF) {
                        end(this);
                        throw new BrainException(BrainException.Kind.CLI, firstErrorLine());
                    }
                    String type = type(line);
                    if (type.equals("result")) return line;
                    if (type.equals("rate_limit_event")) rateLimitEvent = line;
                }
            } catch (InterruptedException e) {
                end(this);
                Thread.currentThread().interrupt();
                throw new BrainException(BrainException.Kind.INTERRUPTED, "interrupted");
            } finally {
                busy = false;
                lastUsed = clock.getAsLong();
            }
        }

        private String firstErrorLine() throws InterruptedException {
            if (errPump != null) errPump.join(500); // the exit message may still be on its way
            synchronized (err) {
                return err.toString().lines().map(String::strip).filter(l -> !l.isEmpty()).findFirst()
                        .map(l -> l.length() > 200 ? l.substring(0, 200) + "..." : l)
                        .orElse("Claude Code exited before answering");
            }
        }
    }

    private final Launcher launcher;
    private final long idleMillis;
    private final LongSupplier clock;
    private final boolean sweepInBackground;
    private Session current;
    private Session spare;
    private boolean closed;
    private ScheduledExecutorService sweeper;
    private volatile String rateLimitEvent;

    /** {@code sweepInBackground} false leaves idle stops to whoever calls {@link #sweep} (tests). */
    ClaudeCodeSessions(Launcher launcher, long idleMillis, LongSupplier clock, boolean sweepInBackground) {
        this.launcher = launcher;
        this.idleMillis = idleMillis;
        this.clock = clock;
        this.sweepInBackground = sweepInBackground;
    }

    /** Ends the current session, then takes the spare if it matches {@code command} (else starts a process). */
    Session take(List<String> command) throws BrainException {
        Session old;
        Session s;
        synchronized (this) {
            old = current;
            current = null;
            s = spare;
            spare = null;
        }
        if (old != null) old.proc.kill();
        if (s != null && (!s.command.equals(command) || !s.proc.alive())) {
            s.proc.kill();
            s = null;
        }
        if (s == null) {
            try {
                s = launch(command);
            } catch (IOException e) {
                throw new BrainException(BrainException.Kind.NO_CLI, command.get(0) + ": " + e.getMessage());
            }
        }
        synchronized (this) {
            if (!closed) {
                current = s;
                return s;
            }
        }
        s.proc.kill();
        throw new BrainException(BrainException.Kind.INTERRUPTED, "shutting down");
    }

    /** Makes sure an idle spare for {@code command} is running. A start failure only costs the next order its head start. */
    void refill(List<String> command) {
        Session stale;
        synchronized (this) {
            if (closed || spare != null && spare.command.equals(command) && spare.proc.alive()) return;
            stale = spare;
            spare = null;
        }
        if (stale != null) stale.proc.kill();
        Session s;
        try {
            s = launch(command);
        } catch (IOException e) {
            LOG.warn("couldn't start a spare Claude Code: {}", e.getMessage());
            return;
        }
        boolean keep;
        synchronized (this) {
            keep = !closed && spare == null;
            if (keep) spare = s;
        }
        if (!keep) s.proc.kill();
    }

    synchronized Session current() {
        return current;
    }

    /** Kills {@code s}, and forgets it if it is the current session. */
    void end(Session s) {
        synchronized (this) {
            if (current == s) current = null;
        }
        s.proc.kill();
    }

    /** Kills the current session, not the spare. Safe from any thread. */
    void endCurrent() {
        Session s;
        synchronized (this) {
            s = current;
            current = null;
        }
        if (s != null) s.proc.kill();
    }

    /** Stops the spare and the current session if unused for the idle time; a session mid-turn is never idle. */
    void sweep() {
        long now = clock.getAsLong();
        List<Session> idle = new ArrayList<>();
        synchronized (this) {
            if (spare != null && now - spare.lastUsed >= idleMillis) {
                idle.add(spare);
                spare = null;
            }
            if (current != null && !current.busy && now - current.lastUsed >= idleMillis) {
                idle.add(current);
                current = null;
            }
        }
        idle.forEach(s -> s.proc.kill());
        if (!idle.isEmpty()) LOG.info("stopped {} idle Claude Code process(es)", idle.size());
    }

    void close() {
        Session a;
        Session b;
        synchronized (this) {
            closed = true;
            a = current;
            b = spare;
            current = null;
            spare = null;
            if (sweeper != null) sweeper.shutdownNow();
        }
        if (a != null) a.proc.kill();
        if (b != null) b.proc.kill();
    }

    synchronized Session spare() {
        return spare;
    }

    /** The last {@code rate_limit_event} line seen (Claude Code sends one on a session's first turn), or null. */
    String rateLimitEvent() {
        return rateLimitEvent;
    }

    private Session launch(List<String> command) throws IOException {
        Session s = new Session(launcher.start(command), command);
        daemon("claudeplayer-cc-out", () -> {
            try (BufferedReader out = reader(s.proc.stdout())) {
                for (String line; (line = out.readLine()) != null; ) s.lines.add(line);
            } catch (IOException ignored) {
                // killed; EOF below
            }
            s.lines.add(EOF);
        });
        s.errPump = daemon("claudeplayer-cc-err", () -> {
            try (Reader err = reader(s.proc.stderr())) {
                char[] buf = new char[1024];
                for (int n; (n = err.read(buf)) >= 0; ) {
                    synchronized (s.err) {
                        if (s.err.length() < 4096) s.err.append(buf, 0, Math.min(n, 4096 - s.err.length()));
                    }
                }
            } catch (IOException ignored) {
                // killed
            }
        });
        startSweeper();
        return s;
    }

    private synchronized void startSweeper() {
        if (!sweepInBackground || sweeper != null || closed) return;
        sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "claudeplayer-cc-idle");
            t.setDaemon(true);
            return t;
        });
        long every = Math.max(1_000, Math.min(idleMillis / 2, 30_000));
        sweeper.scheduleWithFixedDelay(this::sweep, every, every, TimeUnit.MILLISECONDS);
    }

    private static Thread daemon(String name, Runnable body) {
        Thread t = new Thread(body, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static BufferedReader reader(InputStream in) {
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    private static String type(String line) {
        try {
            JsonElement type = JsonParser.parseString(line).getAsJsonObject().get("type");
            return type != null && type.isJsonPrimitive() ? type.getAsString() : "";
        } catch (RuntimeException e) {
            return ""; // not an event; ignore it
        }
    }
}
