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
    @SuppressWarnings("StringOperationCanBeSimplified")
    private static final String WRITE_FAILED = new String("write failed");
    @SuppressWarnings("StringOperationCanBeSimplified")
    private static final String CANCELLED = new String("cancelled"); // a reset: wakes the turn's await at once

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

    /**
     * One model call, from {@link #begin} to {@link #finish}. A reset at any point of it (while a child starts,
     * mid-write, mid-read, even after the answer arrived) cancels it: the call then ends as cancelled, not a reply.
     */
    final class Turn {
        private volatile boolean cancelled;
        private Session session; // guarded by ClaudeCodeSessions.this

        /** Throws the cancellation if a reset came. */
        void check() throws BrainException {
            if (cancelled) throw cancelled();
        }

        /** {@code e}, or the cancellation if a reset caused it. */
        BrainException or(BrainException e) {
            return cancelled ? cancelled() : e;
        }
    }

    /** One warm process. {@code seen} is the transcript prefix it has already read; brain thread only. */
    final class Session {
        final Proc proc;
        final List<String> command;
        JsonArray seen = new JsonArray();
        private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        private final StringBuilder err = new StringBuilder();
        private Thread errPump;
        private volatile String writeError;
        private volatile long lastUsed;
        private boolean claimed; // a call holds it: the sweep leaves it alone. Guarded by ClaudeCodeSessions.this

        private Session(Proc proc, List<String> command) {
            this.proc = proc;
            this.command = command;
            this.lastUsed = clock.getAsLong();
        }

        /**
         * Sends one stream-json line from a writer thread, so a child that never reads its stdin (a cold start that
         * stalls, a full pipe) can't block the caller: {@link #await}'s deadline covers the write, and killing the
         * process frees the writer.
         */
        void write(String line) {
            byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
            daemon("claudeplayer-cc-in", () -> {
                try {
                    OutputStream in = proc.stdin();
                    in.write(bytes);
                    in.flush();
                } catch (IOException e) {
                    writeError = e.getMessage();
                    lines.add(WRITE_FAILED);
                }
            });
        }

        /**
         * Reads events until the turn's {@code result} event and returns it; any failure kills the session.
         * {@code deadline} is a {@link System#nanoTime} instant, set when the turn began. A reset ends it as cancelled.
         */
        String await(long deadline, Turn turn) throws BrainException {
            try {
                while (true) {
                    String line = lines.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                    if (line == CANCELLED || turn.cancelled) {
                        end(this);
                        throw cancelled();
                    }
                    if (line == null) {
                        end(this);
                        throw new BrainException(BrainException.Kind.TIMEOUT, "timed out");
                    }
                    if (line == EOF) {
                        end(this);
                        throw new BrainException(BrainException.Kind.CLI, firstErrorLine());
                    }
                    if (line == WRITE_FAILED) {
                        end(this);
                        throw new BrainException(BrainException.Kind.CLI, "couldn't write the prompt: " + writeError);
                    }
                    String type = type(line);
                    if (type.equals("result")) return line;
                    if (type.equals("rate_limit_event")) rateLimitEvent = line;
                }
            } catch (InterruptedException e) {
                end(this);
                Thread.currentThread().interrupt();
                throw new BrainException(BrainException.Kind.INTERRUPTED, "interrupted");
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
    private Turn turn; // the model call in flight
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

    /** Starts a model call; from here until {@link #finish}, a {@link #endCurrent} cancels it. Brain thread. */
    synchronized Turn begin() {
        turn = new Turn();
        return turn;
    }

    /** Ends the call: throws the cancellation if a reset came during it. A later reset only ends the session. */
    void finish(Turn t) throws BrainException {
        release(t);
        t.check();
    }

    /** Ends the call, whatever happened to it: its session counts as idle from now. */
    synchronized void release(Turn t) {
        if (turn == t) turn = null;
        if (t.session != null) {
            t.session.claimed = false;
            t.session.lastUsed = clock.getAsLong();
            t.session = null;
        }
    }

    /**
     * Ends the current session, then takes the spare if it matches {@code command} (else starts a process), for
     * {@code t}. A reset before or during the start cancels {@code t} and stops the new process.
     */
    Session take(List<String> command, Turn t) throws BrainException {
        Session old;
        Session s;
        synchronized (this) {
            t.check(); // a reset already came: leave the spare for the next order
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
                throw t.or(new BrainException(BrainException.Kind.NO_CLI, command.get(0) + ": " + e.getMessage()));
            }
        }
        BrainException refused;
        synchronized (this) {
            if (closed) {
                refused = new BrainException(BrainException.Kind.INTERRUPTED, "shutting down");
            } else if (t.cancelled) {
                refused = cancelled();
            } else {
                s.claimed = true; // in the same step that makes it current, so the sweep never sees it unclaimed
                current = s;
                t.session = s;
                return s;
            }
        }
        s.proc.kill();
        throw refused;
    }

    /**
     * Claims the current session for {@code t}, or returns null if there is none (the sweep may have just stopped it)
     * or {@code t} was cancelled. Claimed, the sweep can't stop it until {@link #release}.
     */
    synchronized Session claimCurrent(Turn t) {
        if (current == null || t.cancelled) return null;
        current.claimed = true;
        t.session = current;
        return current;
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

    /** Kills {@code s}, and forgets it if it is the current session. */
    void end(Session s) {
        synchronized (this) {
            if (current == s) current = null;
        }
        s.proc.kill();
    }

    /** Cancels the call in flight, if any, and kills the current session, not the spare. Safe from any thread. */
    void endCurrent() {
        Session s;
        Session waiting;
        synchronized (this) {
            s = current;
            current = null;
            waiting = cancelTurn();
        }
        if (waiting != null) waiting.lines.add(CANCELLED);
        if (s != null) s.proc.kill();
        if (waiting != null && waiting != s) waiting.proc.kill();
    }

    /** Marks the call in flight cancelled; returns the session it waits on, if it has one yet. */
    private Session cancelTurn() {
        if (turn == null) return null;
        turn.cancelled = true;
        Session s = turn.session;
        turn = null;
        return s;
    }

    private static BrainException cancelled() {
        return new BrainException(BrainException.Kind.INTERRUPTED, "cancelled");
    }

    /**
     * Stops the spare and the current session if unused for the idle time. A session a call has claimed (or is
     * taking: it is then in neither field) is never idle: claims and this check share the lock.
     */
    void sweep() {
        List<Session> idle = new ArrayList<>();
        synchronized (this) {
            long now = clock.getAsLong();
            if (spare != null && now - spare.lastUsed >= idleMillis) {
                idle.add(spare);
                spare = null;
            }
            if (current != null && !current.claimed && now - current.lastUsed >= idleMillis) {
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
        Session waiting;
        synchronized (this) {
            closed = true;
            a = current;
            b = spare;
            current = null;
            spare = null;
            waiting = cancelTurn();
            if (sweeper != null) sweeper.shutdownNow();
        }
        if (waiting != null) waiting.lines.add(CANCELLED);
        if (a != null) a.proc.kill();
        if (b != null) b.proc.kill();
        if (waiting != null && waiting != a) waiting.proc.kill();
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
