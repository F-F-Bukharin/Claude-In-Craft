package com.example.claudeplayer.core.brain;

import com.example.claudeplayer.core.Settings;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plan option B: the model runs through the player's own Claude Code CLI ({@code claude -p}), so calls use their
 * Claude subscription instead of an API key. Claude Code gets no tools of its own (no Bash, no files, no MCP,
 * no user settings) and a replaced system prompt; it only answers with a JSON object of tool calls, which this
 * class turns back into an ordinary {@link Reply}.
 *
 * <p>Each order gets one warm, unsaved stream-json session ({@link ClaudeCodeSessions}): the opening turn goes to a
 * spare process started ahead, and later turns send only the messages the session hasn't read. The loop's
 * append-only Messages transcript stays the single source of truth: if it no longer starts with what the session
 * saw, the session is replaced by a fresh one that reads the whole transcript.
 */
public final class ClaudeCodeBrain implements Brain {
    private static final Logger LOG = LoggerFactory.getLogger(ClaudeCodeBrain.class);
    private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");
    static final String SCHEMA = "{\"type\":\"object\",\"properties\":{"
            + "\"say\":{\"type\":\"string\",\"description\":\"Your chat report. Shown to players only when calls is empty.\"},"
            + "\"calls\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{"
            + "\"name\":{\"type\":\"string\"},\"input\":{\"type\":\"object\"}},\"required\":[\"name\",\"input\"]}}},"
            + "\"required\":[\"say\",\"calls\"]}";
    // Without this, Haiku tried a game action as a native tool on 4 of 4 opening turns (measured 23 Oct): a wasted
    // round trip, and often an "I can't move" reply with no actions. With it and the compact list: 0 of 16.
    private static final String ONLY_TOOL = "Your only callable tool is StructuredOutput: every reply is exactly one "
            + "StructuredOutput call. The game actions below (move_to, say, look and the rest) are not tools. You request "
            + "them by listing them in the calls array of that answer, and the game runs them.\n\n";
    private static final String HOW_TO_ACT = """


            # How you act
            Answer with {"say": ..., "calls": [...]}. Each entry in calls is {"name": <action>, "input": {...}}, with \
            input as shown below; add "prio": 1, 2 or 3 to input when it matters. The results come back in the next \
            turn. When the order is finished, or you can't go on, leave calls empty and put your report in say.

            # Actions (list them in calls; never call them directly)
            """;

    private final Settings settings;
    private final Path workDir;
    private final ClaudeCodeEnv env;
    private final ClaudeCodeSessions sessions;
    private final AtomicLong replies = new AtomicLong();

    /** {@code workDir} must sit outside any project, so Claude Code finds no CLAUDE.md to load. */
    public ClaudeCodeBrain(Settings settings, Path workDir) {
        this.settings = settings;
        this.workDir = workDir;
        this.env = new ClaudeCodeEnv(workDir);
        env.killLeftovers();
        this.sessions = new ClaudeCodeSessions(env, settings.claudeCodeIdleMinutes() * 60_000L, System::currentTimeMillis, true);
    }

    /** Tests: scripted processes, a fake clock, and idle stops only when the test sweeps. */
    ClaudeCodeBrain(Settings settings, Path workDir, ClaudeCodeSessions.Launcher launcher, LongSupplier clock) {
        this.settings = settings;
        this.workDir = workDir;
        this.env = new ClaudeCodeEnv(workDir);
        this.sessions = new ClaudeCodeSessions(launcher, settings.claudeCodeIdleMinutes() * 60_000L, clock, false);
    }

    @Override
    public Reply send(Request request) throws BrainException {
        List<String> command = streamCommand(request.system(), request.tools());
        JsonArray messages = request.messages();
        long started = System.nanoTime();
        ClaudeCodeSessions.Session session = sessions.current();
        int from = session == null ? -1 : continues(session.seen, messages);
        if (from < 0) {
            if (messages.size() > 1) {
                LOG.info("claude code session restarts from the whole transcript ({} messages)", messages.size());
            }
            session = sessions.take(command);
            from = 0;
        }
        session.write(userLine(renderTranscript(messages, from)));
        sessions.refill(command); // the next spare starts while the model thinks
        Reply reply;
        try {
            reply = parseResult(session.await(settings.claudeCodeTimeoutSeconds() * 1000L), "cc" + replies.incrementAndGet() + "_");
        } catch (BrainException e) {
            sessions.end(session);
            throw e;
        }
        if (reply.toolCalls().isEmpty()) {
            sessions.end(session); // end_turn: the order is over
        } else {
            JsonArray seen = messages.deepCopy();
            JsonObject answer = new JsonObject(); // what the loop appends for this reply
            answer.addProperty("role", "assistant");
            answer.add("content", reply.content().deepCopy());
            seen.add(answer);
            session.seen = seen;
        }
        Usage u = reply.usage();
        LOG.info("claude code reply {}: stop={} calls={} ms={} tokens in={} out={} cache_write={} cache_read={}", reply.id(),
                reply.stopReason(), reply.toolCalls().size(), (System.nanoTime() - started) / 1_000_000, u.input(),
                u.output(), u.cacheWrite(), u.cacheRead());
        return reply;
    }

    /** Kills the current order's session (the spare stays). Only destroys processes, so any thread may call it. */
    @Override
    public void reset() {
        sessions.endCurrent();
    }

    /** Starts the spare ahead of the first order, so even that one skips startup. Brain thread. */
    public void prewarm(String system, JsonArray tools) {
        try {
            sessions.refill(streamCommand(system, tools));
        } catch (BrainException e) {
            LOG.debug("no Claude Code spare: {}", e.getMessage());
        }
    }

    /** Server shutdown: stops every warm process. */
    public void close() {
        sessions.close();
    }

    /** Tests: the idle sweep the background timer runs. */
    void sweepIdle() {
        sessions.sweep();
    }

    /** The last {@code rate_limit_event} JSON Claude Code sent, or null. For a later usage ledger. */
    public String rateLimitEvent() {
        return sessions.rateLimitEvent();
    }

    /** For /cp keycheck: one tiny call that proves the CLI is found and logged in. */
    public String check() throws BrainException {
        Path systemFile = writeWork("check.md", "Reply with say set to OK and calls empty.");
        parseResult(run(systemFile, "ping"), "check_");
        return "Claude Code works with " + settings.model() + " (" + executable() + ").";
    }

    /** The configured path, else claude.exe / claude on PATH, else the native installer's ~/.local/bin. */
    public String executable() {
        String configured = settings.claudeCodePath();
        if (configured != null && !configured.isBlank()) return configured.strip();
        List<String> names = WINDOWS ? List.of("claude.exe", "claude") : List.of("claude");
        for (String dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            for (String name : names) {
                if (dir.isBlank()) continue;
                Path p = Path.of(dir.strip(), name);
                if (Files.isRegularFile(p)) return p.toString();
            }
        }
        Path local = Path.of(System.getProperty("user.home"), ".local", "bin", WINDOWS ? "claude.exe" : "claude");
        return Files.isRegularFile(local) ? local.toString() : null;
    }

    /** The one-shot command (keycheck): the prompt on stdin, one JSON result on stdout. */
    static List<String> command(String exe, Settings s, Path systemFile, boolean windows) {
        return command(exe, s, systemFile, windows, false);
    }

    /** {@code stream}: a warm session that reads stream-json user lines and writes stream-json events. */
    static List<String> command(String exe, Settings s, Path systemFile, boolean windows, boolean stream) {
        List<String> cmd = new ArrayList<>(stream
                ? List.of(exe, "-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose")
                : List.of(exe, "-p", "--output-format", "json"));
        cmd.addAll(List.of("--tools", "", "--strict-mcp-config", "--setting-sources", "", "--no-session-persistence",
                "--disable-slash-commands", "--system-prompt-file", systemFile.toString(),
                // Windows rebuilds one command line from these; without \" the JSON loses its quotes.
                "--json-schema", windows ? SCHEMA.replace("\"", "\\\"") : SCHEMA));
        if (s.model() != null && !s.model().isBlank()) cmd.addAll(List.of("--model", s.model()));
        if (s.effort() != null && !s.effort().isBlank()) cmd.addAll(List.of("--effort", s.effort()));
        return cmd;
    }

    /**
     * The game's prompt plus one line per action, e.g. {@code - move_to {"x": number, "y": number, "z": number}: Walk...}.
     * Full JSON schemas read like native tools and doubled the prompt; the shared optional prio is explained once.
     */
    static String renderSystem(String system, JsonArray tools) {
        StringBuilder sb = new StringBuilder(ONLY_TOOL).append(system).append(HOW_TO_ACT);
        for (JsonElement e : tools) {
            if (!e.isJsonObject()) continue;
            JsonObject tool = e.getAsJsonObject();
            sb.append("- ").append(string(tool, "name")).append(' ').append(signature(tool)).append(": ")
                    .append(string(tool, "description")).append('\n');
        }
        return sb.toString();
    }

    /** The required inputs and their types, as a JSON-like object: {@code {"x": number, "item": string}}. */
    static String signature(JsonObject tool) {
        JsonElement rawSchema = tool.get("input_schema");
        if (rawSchema == null || !rawSchema.isJsonObject()) return "{}";
        JsonObject schema = rawSchema.getAsJsonObject();
        JsonObject props = schema.has("properties") && schema.get("properties").isJsonObject()
                ? schema.getAsJsonObject("properties") : new JsonObject();
        List<String> parts = new ArrayList<>();
        if (schema.has("required") && schema.get("required").isJsonArray()) {
            for (JsonElement r : schema.getAsJsonArray("required")) {
                String name = r.getAsString();
                JsonElement prop = props.get(name);
                String type = prop != null && prop.isJsonObject() ? string(prop.getAsJsonObject(), "type") : "";
                parts.add("\"" + name + "\": " + (type.isEmpty() ? "any" : type));
            }
        }
        return "{" + String.join(", ", parts) + "}";
    }

    /** The Messages transcript as plain text: game turns, your earlier replies, and tool results by name. */
    static String renderTranscript(JsonArray messages) {
        return renderTranscript(messages, 0);
    }

    /** Only the messages from {@code from} on; earlier ones still name the tools their results refer to. */
    static String renderTranscript(JsonArray messages, int from) {
        Map<String, String> toolNames = new HashMap<>();
        StringBuilder sb = new StringBuilder();
        StringBuilder skipped = new StringBuilder(); // already read: rendered only to learn tool names
        for (int i = 0; i < messages.size(); i++) {
            if (!messages.get(i).isJsonObject()) continue;
            StringBuilder to = i < from ? skipped : sb;
            JsonObject message = messages.get(i).getAsJsonObject();
            boolean assistant = string(message, "role").equals("assistant");
            to.append(assistant ? "[you]\n" : "[game]\n");
            JsonElement content = message.get("content");
            if (content != null && content.isJsonPrimitive()) {
                to.append(content.getAsString()).append('\n');
            } else if (content != null && content.isJsonArray()) {
                for (JsonElement b : content.getAsJsonArray()) {
                    if (b.isJsonObject()) renderBlock(b.getAsJsonObject(), toolNames, to);
                }
            }
            to.append('\n');
        }
        return sb.append("Answer with your next step as JSON.").toString();
    }

    /** One stream-json user line carrying {@code text}. */
    static String userLine(String text) {
        JsonArray content = new JsonArray();
        content.add(textBlock(text));
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.add("content", content);
        JsonObject line = new JsonObject();
        line.addProperty("type", "user");
        line.add("message", message);
        return line.toString();
    }

    /** Where {@code messages} goes on from what a session saw, or -1 if it doesn't start with exactly that. */
    static int continues(JsonArray seen, JsonArray messages) {
        if (seen.isEmpty() || messages.size() <= seen.size()) return -1;
        for (int i = 0; i < seen.size(); i++) {
            if (!seen.get(i).equals(messages.get(i))) return -1;
        }
        return seen.size();
    }

    private static void renderBlock(JsonObject block, Map<String, String> toolNames, StringBuilder sb) {
        switch (string(block, "type")) {
            case "text" -> sb.append(string(block, "text")).append('\n');
            case "tool_use" -> {
                toolNames.put(string(block, "id"), string(block, "name"));
                sb.append("call ").append(string(block, "name")).append(' ')
                        .append(block.has("input") ? block.get("input") : "{}").append('\n');
            }
            case "tool_result" -> {
                String name = toolNames.getOrDefault(string(block, "tool_use_id"), "tool");
                sb.append("result of ").append(name).append(block.has("is_error") ? " (error)" : "").append(": ")
                        .append(resultText(block.get("content"))).append('\n');
            }
            default -> { } // thinking and other blocks don't exist on this path
        }
    }

    /** Turns the CLI's {@code --output-format json} result into a Reply, or the right BrainException. */
    static Reply parseResult(String out, String idPrefix) throws BrainException {
        JsonObject json;
        try {
            json = JsonParser.parseString(out.strip()).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            String first = out.strip().lines().findFirst().orElse("no output");
            throw new BrainException(BrainException.Kind.CLI, "unreadable Claude Code output: " + clip(first));
        }
        JsonElement structured = json.get("structured_output");
        boolean failed = bool(json, "is_error") || !string(json, "subtype").equals("success")
                || structured == null || !structured.isJsonObject();
        if (failed) {
            int status = integer(json, "api_error_status");
            String message = clip(string(json, "result").isBlank() ? string(json, "subtype") : string(json, "result"));
            BrainException.Kind kind = switch (status) {
                case 429 -> BrainException.Kind.RATE_LIMITED;
                case 529 -> BrainException.Kind.OVERLOADED;
                default -> status >= 500 ? BrainException.Kind.SERVER : BrainException.Kind.CLI;
            };
            throw new BrainException(kind, status, message, string(json, "session_id"));
        }
        JsonObject answer = structured.getAsJsonObject();
        JsonArray content = new JsonArray();
        List<ToolCall> calls = new ArrayList<>();
        String say = string(answer, "say").strip();
        if (!say.isEmpty()) content.add(textBlock(say));
        JsonElement rawCalls = answer.get("calls");
        if (rawCalls != null && rawCalls.isJsonArray()) {
            for (JsonElement e : rawCalls.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                JsonObject c = e.getAsJsonObject();
                JsonElement input = c.get("input");
                ToolCall call = new ToolCall(idPrefix + calls.size(), string(c, "name"),
                        input != null && input.isJsonObject() ? input.getAsJsonObject() : new JsonObject());
                calls.add(call);
                JsonObject block = new JsonObject();
                block.addProperty("type", "tool_use");
                block.addProperty("id", call.id());
                block.addProperty("name", call.name());
                block.add("input", call.input());
                content.add(block);
            }
        }
        JsonElement rawUsage = json.get("usage");
        JsonObject usage = rawUsage != null && rawUsage.isJsonObject() ? rawUsage.getAsJsonObject() : new JsonObject();
        return new Reply(string(json, "session_id"), content, List.copyOf(calls), say,
                calls.isEmpty() ? "end_turn" : "tool_use", new Usage(integer(usage, "input_tokens"),
                integer(usage, "output_tokens"), integer(usage, "cache_creation_input_tokens"),
                integer(usage, "cache_read_input_tokens")));
    }

    private String run(Path systemFile, String prompt) throws BrainException {
        String exe = executable();
        if (exe == null) {
            throw new BrainException(BrainException.Kind.NO_CLI, "claude not found");
        }
        // Both streams go to files, so waitFor's timeout holds even if the CLI hangs mid-output.
        Path stdout = workDir.resolve("reply.json");
        Path stderr = workDir.resolve("stderr.txt");
        Process process;
        try {
            process = env.builder(command(exe, settings, systemFile, WINDOWS))
                    .redirectOutput(stdout.toFile())
                    .redirectError(stderr.toFile())
                    .start();
        } catch (IOException e) {
            throw new BrainException(BrainException.Kind.NO_CLI, exe + ": " + e.getMessage());
        }
        try (OutputStream in = process.getOutputStream()) {
            in.write(prompt.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            process.destroyForcibly();
            throw new BrainException(BrainException.Kind.CLI, "couldn't write the prompt: " + e.getMessage());
        }
        try {
            if (!process.waitFor(settings.claudeCodeTimeoutSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new BrainException(BrainException.Kind.TIMEOUT, "timed out");
            }
            String out = Files.readString(stdout, StandardCharsets.UTF_8);
            if (out.isBlank()) {
                String err = Files.exists(stderr) ? Files.readString(stderr, StandardCharsets.UTF_8).strip() : "";
                throw new BrainException(BrainException.Kind.CLI,
                        "exit " + process.exitValue() + (err.isEmpty() ? "" : ": " + clip(err.lines().findFirst().orElse(""))));
            }
            return out;
        } catch (IOException e) {
            process.destroyForcibly();
            throw new BrainException(BrainException.Kind.CLI, e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new BrainException(BrainException.Kind.INTERRUPTED, "interrupted");
        }
    }

    /**
     * The warm-session command. The system prompt file is named by its content's hash, so a changed prompt is a
     * changed command (the spare gets replaced) and a file a running process reads is never rewritten.
     */
    private List<String> streamCommand(String system, JsonArray tools) throws BrainException {
        String exe = executable();
        if (exe == null) {
            throw new BrainException(BrainException.Kind.NO_CLI, "claude not found");
        }
        String text = renderSystem(system, tools);
        String name = "system-" + sha256(text).substring(0, 16) + ".md";
        Path file = Files.isRegularFile(workDir.resolve(name)) ? workDir.resolve(name) : writeWork(name, text);
        return command(exe, settings, file, WINDOWS, true);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // every JVM ships SHA-256
        }
    }

    private Path writeWork(String name, String text) throws BrainException {
        try {
            Files.createDirectories(workDir);
            Path file = workDir.resolve(name);
            Files.writeString(file, text, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new BrainException(BrainException.Kind.INTERNAL, "couldn't write " + name + ": " + e.getMessage());
        }
    }

    private static String resultText(JsonElement content) {
        if (content == null || content.isJsonNull()) return "";
        if (content.isJsonPrimitive()) return content.getAsString();
        StringBuilder sb = new StringBuilder();
        if (content.isJsonArray()) {
            for (JsonElement e : content.getAsJsonArray()) {
                if (e.isJsonObject()) sb.append(string(e.getAsJsonObject(), "text"));
            }
        }
        return sb.toString();
    }

    private static String clip(String s) {
        String one = s.replaceAll("\\s+", " ").strip();
        return one.length() > 200 ? one.substring(0, 200) + "..." : one;
    }

    private static JsonObject textBlock(String text) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "text");
        o.addProperty("text", text);
        return o;
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    private static int integer(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsInt() : 0;
    }

    private static boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsBoolean();
    }
}
