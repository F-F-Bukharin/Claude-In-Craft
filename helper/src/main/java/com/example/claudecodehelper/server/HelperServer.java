package com.example.claudecodehelper.server;

import com.example.claudecodehelper.ClaudeCode;
import com.example.claudecodehelper.ClaudeCodeException;
import com.example.claudecodehelper.Json;
import com.example.claudecodehelper.Options;
import com.example.claudecodehelper.Reply;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * A Messages-API-style endpoint on 127.0.0.1 backed by {@link ClaudeCode}: {@code POST /v1/messages} and
 * {@code GET /health}. Each request is one turn: a warm session continues when the request's messages extend the
 * previous request's, else a fresh one reads the whole history.
 *
 * <p>Logs carry the request id, model, duration, token counts and stop reason only: never prompt text, image data
 * or the token.
 */
public final class HelperServer {
    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final Logger LOG = Logger.getLogger("claude-code-helper");
    static final long MAX_BODY = 32L * 1024 * 1024;

    /**
     * @param port 0 picks a free port (tests)
     * @param token null accepts any key
     * @param defaultModel used when a request names no model; null leaves Claude Code's default
     */
    public record Config(int port, String token, String defaultModel) {}

    private final ClaudeCode cc;
    private final Config config;
    private final Supplier<JsonObject> claudeStatus;
    private final HttpServer server;
    private final ExecutorService pool;

    public HelperServer(ClaudeCode cc, Config config, Supplier<JsonObject> claudeStatus) throws IOException {
        this.cc = cc;
        this.config = config;
        this.claudeStatus = claudeStatus;
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), config.port()), 32);
        this.pool = Executors.newFixedThreadPool(cc.maxSessions() + 4, r -> {
            Thread t = new Thread(r, "claude-code-http");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(pool);
        server.createContext("/", this::handle);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        server.stop(0);
        pool.shutdownNow();
    }

    private void handle(HttpExchange ex) throws IOException {
        String requestId = Ids.next("req_");
        long started = System.nanoTime();
        String route = route(ex);
        try (ex) {
            ex.getResponseHeaders().set("request-id", requestId);
            try {
                String why = Auth.notLocal(ex.getRequestHeaders(), port());
                if (why != null) throw new ApiError(403, "permission_error", why);
                if (route.equals("/health")) {
                    if (!ex.getRequestMethod().equals("GET")) throw new ApiError(405, "invalid_request_error", "use GET");
                    send(ex, 200, "application/json", GSON.toJson(health()));
                    access(requestId, route, 200, null, started, null, null);
                    return;
                }
                if (!route.equals("/v1/messages")) throw new ApiError(404, "not_found_error", "no such endpoint; use POST /v1/messages");
                if (!ex.getRequestMethod().equals("POST")) throw new ApiError(405, "invalid_request_error", "use POST");
                if (!Auth.allowed(ex.getRequestHeaders(), config.token())) {
                    throw new ApiError(401, "authentication_error", "missing or wrong token: send it as x-api-key or Authorization: Bearer");
                }
                String type = ex.getRequestHeaders().getFirst("Content-Type");
                if (type == null || !type.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
                    throw ApiError.invalid("Content-Type must be application/json");
                }
                messages(ex, requestId, started);
            } catch (ApiError e) {
                send(ex, e.status, "application/json", GSON.toJson(e.toJson()));
                access(requestId, route, e.status, e.type, started, null, null);
            }
        } catch (IOException | RuntimeException e) {
            LOG.warning(requestId + " " + route + " failed: " + e.getClass().getSimpleName());
        }
    }

    private void messages(HttpExchange ex, String requestId, long started) throws ApiError, IOException {
        JsonObject body = parse(read(ex.getRequestBody()));
        JsonArray messages = checkMessages(body.get("messages"));
        String model = Json.str(body, "model").isBlank() ? config.defaultModel() : Json.str(body, "model");
        boolean stream = Json.bool(body, "stream");
        if (body.has("stream") && !(body.get("stream").isJsonPrimitive() && body.get("stream").getAsJsonPrimitive().isBoolean())) {
            throw ApiError.invalid("stream must be a boolean");
        }
        Tools tools = Tools.plan(body.get("tools"), body.get("tool_choice"));
        String system = Render.system(body.get("system"));
        if (tools != null) system = (system.isBlank() ? Options.DEFAULT_SYSTEM : system) + "\n\n" + tools.section();
        Options options;
        try {
            options = cc.defaults().withModel(model).withSystemPrompt(system).withJsonSchema(tools == null ? null : tools.schema());
        } catch (IllegalArgumentException e) {
            throw ApiError.invalid(e.getMessage());
        }

        JsonArray whole = Render.turn(messages, 0); // checks every block before a session is taken
        JsonObject message;
        boolean reused;
        try (ClaudeCode.Lease lease = cc.lease(options, messages)) {
            reused = lease.reused();
            JsonArray turn = reused ? Render.turn(messages, lease.from()) : whole;
            Reply reply;
            try {
                reply = lease.send(turn);
            } catch (IllegalArgumentException e) {
                throw ApiError.invalid(e.getMessage());
            }
            JsonArray content;
            String stop;
            if (tools != null && tools.schema() != null) {
                content = tools.content(reply.structured(), () -> Ids.next("toolu_"));
                stop = content.asList().stream().anyMatch(b -> Json.str(b.getAsJsonObject(), "type").equals("tool_use"))
                        ? "tool_use" : "end_turn";
            } else {
                content = new JsonArray();
                content.add(Json.text(reply.text()));
                stop = "max_tokens".equals(reply.stopReason()) ? "max_tokens" : "end_turn";
            }
            message = new JsonObject();
            message.addProperty("id", Ids.next("msg_"));
            message.addProperty("type", "message");
            message.addProperty("role", "assistant");
            message.addProperty("model", reply.model() == null || reply.model().isBlank()
                    ? (model == null ? "claude-code-default" : model) : reply.model());
            message.add("content", content);
            message.addProperty("stop_reason", stop);
            message.add("stop_sequence", null);
            message.add("usage", reply.usage().toJson());

            JsonArray seen = messages.deepCopy();
            JsonObject assistant = new JsonObject();
            assistant.addProperty("role", "assistant");
            assistant.add("content", content.deepCopy());
            seen.add(assistant);
            lease.keep(seen);
        } catch (ClaudeCodeException e) {
            throw ApiError.from(e);
        }

        if (stream) {
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            send(ex, 200, "text/event-stream; charset=utf-8", Sse.events(message));
        } else {
            send(ex, 200, "application/json", GSON.toJson(message));
        }
        access(requestId, "/v1/messages", 200, null, started, message, reused ? "reused" : "fresh");
    }

    /** Messages: a non-empty array of {role, content}, user and assistant only, ending with a user turn. */
    static JsonArray checkMessages(JsonElement raw) throws ApiError {
        if (raw == null || !raw.isJsonArray() || raw.getAsJsonArray().isEmpty()) {
            throw ApiError.invalid("messages: a non-empty array is required");
        }
        JsonArray messages = raw.getAsJsonArray();
        for (int i = 0; i < messages.size(); i++) {
            if (!messages.get(i).isJsonObject()) throw ApiError.invalid("messages." + i + ": must be an object");
            JsonObject m = messages.get(i).getAsJsonObject();
            String role = Json.str(m, "role");
            if (!role.equals("user") && !role.equals("assistant")) {
                throw ApiError.invalid("messages." + i + ".role: must be user or assistant");
            }
            JsonElement c = m.get("content");
            boolean ok = c != null && (c.isJsonArray() || c.isJsonPrimitive() && c.getAsJsonPrimitive().isString());
            if (!ok) throw ApiError.invalid("messages." + i + ".content: a string or an array of blocks is required");
        }
        if (!Json.str(messages.get(messages.size() - 1).getAsJsonObject(), "role").equals("user")) {
            throw ApiError.invalid("the last message must be a user message (assistant prefill isn't supported)");
        }
        return messages;
    }

    private JsonObject health() {
        JsonObject o = new JsonObject();
        JsonObject claude = claudeStatus.get();
        boolean ok = Json.bool(claude, "found") && !(claude.has("signed_in") && claude.get("signed_in").isJsonPrimitive()
                && !claude.get("signed_in").getAsBoolean());
        o.addProperty("status", ok ? "ok" : "degraded");
        o.addProperty("version", Main.VERSION);
        o.add("claude", claude);
        JsonObject sessions = new JsonObject();
        sessions.addProperty("live", cc.liveSessions());
        sessions.addProperty("max", cc.maxSessions());
        o.add("sessions", sessions);
        o.addProperty("token_required", config.token() != null);
        return o;
    }

    /** One line per request: ids, model, time, tokens, stop reason. Never content. */
    private void access(String requestId, String route, int status, String errorType, long started, JsonObject message,
                        String session) {
        StringBuilder sb = new StringBuilder(requestId).append(' ').append(route).append(' ').append(status);
        if (errorType != null) sb.append(' ').append(errorType);
        if (message != null) {
            JsonObject u = message.getAsJsonObject("usage");
            sb.append(" model=").append(Json.str(message, "model")).append(" session=").append(session)
                    .append(" in=").append(Json.integer(u, "input_tokens")).append(" out=").append(Json.integer(u, "output_tokens"))
                    .append(" cache_read=").append(Json.integer(u, "cache_read_input_tokens"))
                    .append(" cache_write=").append(Json.integer(u, "cache_creation_input_tokens"))
                    .append(" stop=").append(Json.str(message, "stop_reason"));
        }
        sb.append(" ms=").append((System.nanoTime() - started) / 1_000_000);
        LOG.info(sb.toString());
    }

    private static String route(HttpExchange ex) {
        String path = ex.getRequestURI().getPath();
        if (path == null) return "/";
        if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return path.equals("/health") || path.equals("/v1/messages") ? path : "other";
    }

    private static String read(InputStream in) throws IOException, ApiError {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[65536];
        for (int n; (n = in.read(chunk)) >= 0; ) {
            buf.write(chunk, 0, n);
            if (buf.size() > MAX_BODY) throw new ApiError(413, "request_too_large", "the request is over 32 MB");
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    private static JsonObject parse(String body) throws ApiError {
        try {
            JsonElement e = JsonParser.parseString(body);
            if (!e.isJsonObject()) throw ApiError.invalid("the body must be a JSON object");
            return e.getAsJsonObject();
        } catch (RuntimeException e) {
            throw ApiError.invalid("the body isn't valid JSON");
        }
    }

    private static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
