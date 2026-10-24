package com.example.claudecodehelper;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;

/** A scripted stand-in for {@code claude}: each stdin line is answered with the next scripted list of events. */
public final class FakeClaude implements Launcher {
    public static final String EXIT_NOT_LOGGED_IN = "exit";
    public static final String RATE = "{\"type\":\"rate_limit_event\",\"rate_limit_info\":{\"status\":\"allowed\","
            + "\"unifiedWindows\":{\"five_hour\":{\"utilization\":0.1}}}}";

    public final List<Proc> procs = Collections.synchronizedList(new ArrayList<>());
    public final List<List<String>> commands = Collections.synchronizedList(new ArrayList<>());
    public final ConcurrentLinkedDeque<List<String>> script = new ConcurrentLinkedDeque<>();
    /** When set, start() waits on it (a slow process start). */
    public volatile CountDownLatch startGate;
    /** When true, new processes never read stdin: a write blocks forever. */
    public volatile boolean stallStdin;
    /** When set, kill() waits on it (a slow tree walk). */
    public volatile CountDownLatch killGate;
    public volatile String killThread;

    @Override
    public Proc start(List<String> command) throws IOException {
        CountDownLatch gate = startGate;
        if (gate != null) {
            try {
                gate.await();
            } catch (InterruptedException e) {
                throw new IOException("interrupted");
            }
        }
        commands.add(command);
        Proc p = new Proc(stallStdin);
        procs.add(p);
        return p;
    }

    /** Waits up to 10 s for {@code done}, else fails the test. */
    public static void await(java.util.function.BooleanSupplier done) {
        long end = System.nanoTime() + 10_000_000_000L;
        while (!done.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("timed out waiting");
            Thread.onSpinWait();
        }
    }

    public Proc proc(int i) {
        return procs.get(i);
    }

    /** A successful result event with this text (and structured output when given). */
    public static String result(String text, int cacheRead, String structured) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "result");
        o.addProperty("subtype", "success");
        o.addProperty("is_error", false);
        o.addProperty("session_id", "s-1");
        o.addProperty("stop_reason", "end_turn");
        o.addProperty("result", text);
        JsonObject usage = new JsonObject();
        usage.addProperty("input_tokens", 3);
        usage.addProperty("output_tokens", 40);
        usage.addProperty("cache_creation_input_tokens", 100);
        usage.addProperty("cache_read_input_tokens", cacheRead);
        o.add("usage", usage);
        if (structured != null) o.add("structured_output", JsonParser.parseString(structured));
        return o.toString();
    }

    public static String result(String text) {
        return result(text, 0, null);
    }

    /** The content blocks of a written stream-json user line. */
    public static JsonArray content(String line) {
        JsonObject o = JsonParser.parseString(line).getAsJsonObject();
        if (!o.get("type").getAsString().equals("user")) throw new AssertionError("not a user line: " + line);
        return o.getAsJsonObject("message").getAsJsonArray("content");
    }

    /** All text of a written line's blocks, joined. */
    public static String text(String line) {
        StringBuilder sb = new StringBuilder();
        for (var b : content(line)) {
            JsonObject block = b.getAsJsonObject();
            if (block.get("type").getAsString().equals("text")) sb.append(block.get("text").getAsString());
        }
        return sb.toString();
    }

    /** A pipe end the fake writes and the library reads; closing it is EOF. */
    static final class Pipe extends InputStream {
        private final BlockingQueue<Integer> bytes = new LinkedBlockingQueue<>();

        void write(String s) {
            for (byte b : s.getBytes(StandardCharsets.UTF_8)) bytes.add(b & 0xff);
        }

        void close0() {
            bytes.add(-1);
        }

        @Override
        public int read() {
            try {
                int b = bytes.take();
                if (b < 0) bytes.add(-1);
                return b;
            } catch (InterruptedException e) {
                return -1;
            }
        }

        @Override
        public int read(byte[] b, int off, int len) {
            int first = read();
            if (first < 0) return -1;
            b[off] = (byte) first;
            int n = 1;
            while (n < len && bytes.peek() != null && bytes.peek() >= 0) b[off + n++] = (byte) (int) bytes.poll();
            return n;
        }
    }

    public final class Proc implements Launcher.Proc {
        public final List<String> written = Collections.synchronizedList(new ArrayList<>());
        final Pipe out = new Pipe();
        final Pipe err = new Pipe();
        public volatile boolean alive = true;
        private final boolean stall;
        private final CountDownLatch never = new CountDownLatch(1);

        Proc(boolean stall) {
            this.stall = stall;
        }

        /** Writes raw stdout (for oversized lines). */
        public void emit(String s) {
            out.write(s);
        }

        @Override
        public OutputStream stdin() {
            return new OutputStream() {
                private final ByteArrayOutputStream line = new ByteArrayOutputStream();

                @Override
                public void write(int b) throws IOException {
                    if (stall) {
                        try {
                            never.await(); // a child that never reads stdin; only kill() frees it
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        throw new IOException("pipe closed");
                    }
                    if (b != '\n') {
                        line.write(b);
                        return;
                    }
                    written.add(line.toString(StandardCharsets.UTF_8));
                    line.reset();
                    List<String> events = script.poll();
                    if (events == null) return;
                    for (String e : events) {
                        if (e.equals(EXIT_NOT_LOGGED_IN)) {
                            err.write("Not logged in · Please run /login\nmore\n");
                            err.close0();
                            out.close0();
                            alive = false;
                        } else {
                            out.write(e + "\n");
                        }
                    }
                }
            };
        }

        @Override
        public InputStream stdout() {
            return out;
        }

        @Override
        public InputStream stderr() {
            return err;
        }

        @Override
        public boolean alive() {
            return alive;
        }

        @Override
        public void kill() {
            killThread = Thread.currentThread().getName();
            CountDownLatch gate = killGate;
            if (gate != null) {
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            alive = false;
            never.countDown();
            out.close0();
            err.close0();
        }
    }
}
