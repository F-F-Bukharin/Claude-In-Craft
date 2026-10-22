package com.example.claudeplayer.core.brain;

import com.example.claudeplayer.core.Settings;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Messages API over java.net.http and Gson, both already in Minecraft's runtime, so nothing gets shaded.
 * Retries 429, 5xx, timeouts and network errors after 2 s and then 4 s (or the server's retry-after). Logs ids,
 * stop reasons and token counts; full request bodies only at DEBUG with log_prompts on. Never logs the key.
 */
public final class ApiBrain implements Brain {
    private static final Logger LOG = LoggerFactory.getLogger(ApiBrain.class);
    static final URI MESSAGES = URI.create("https://api.anthropic.com/v1/messages");
    static final URI COUNT_TOKENS = URI.create("https://api.anthropic.com/v1/messages/count_tokens");
    static final String API_VERSION = "2026-10-21";
    private static final long[] BACKOFF_MS = {2000, 4000};

    private final Settings settings;
    private final Supplier<String> apiKey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public ApiBrain(Settings settings, Supplier<String> apiKey) {
        this.settings = settings;
        this.apiKey = apiKey;
    }

    @Override
    public Reply send(Request request) throws BrainException {
        JsonObject body = requestBody(request, settings);
        if (settings.logPrompts()) LOG.debug("Messages API request: {}", body);
        Reply reply = parseReply(post(MESSAGES, body));
        Usage u = reply.usage();
        LOG.info("model reply {}: stop={} calls={} tokens in={} out={} cache_write={} cache_read={}", reply.id(),
                reply.stopReason(), reply.toolCalls().size(), u.input(), u.output(), u.cacheWrite(), u.cacheRead());
        return reply;
    }

    /** For /cp keycheck: count_tokens costs nothing and fails the same way on a bad key or model id. */
    public int checkKey() throws BrainException {
        JsonObject ping = new JsonObject();
        ping.addProperty("role", "user");
        ping.addProperty("content", "ping");
        JsonArray messages = new JsonArray();
        messages.add(ping);
        JsonObject body = new JsonObject();
        body.addProperty("model", settings.model());
        body.add("messages", messages);
        JsonElement tokens = post(COUNT_TOKENS, body).get("input_tokens");
        return tokens == null || tokens.isJsonNull() ? 0 : tokens.getAsInt();
    }

    /** The key from the env var named by api_key_env, else the first non-blank line of {@code keyFile}. */
    public static String resolveKey(String envName, Path keyFile) {
        String env = envName == null || envName.isBlank() ? null : System.getenv(envName);
        if (env != null && !env.isBlank()) return env.strip();
        try {
            if (keyFile != null && Files.isRegularFile(keyFile)) {
                for (String line : Files.readAllLines(keyFile)) {
                    if (!line.isBlank()) return line.strip();
                }
            }
        } catch (IOException e) {
            LOG.warn("Couldn't read the key file {}: {}", keyFile, e.getMessage());
        }
        return null;
    }

    static JsonObject requestBody(Request request, Settings s) {
        JsonObject body = new JsonObject();
        body.addProperty("model", s.model());
        body.addProperty("max_tokens", s.maxTokens());
        JsonObject system = text(request.system());
        // One breakpoint on the system block caches tools + system across orders; one on the newest turn caches
        // the order's transcript between rounds. Below the model's minimum prefix the API simply doesn't cache.
        if (s.promptCaching()) system.add("cache_control", ephemeral());
        JsonArray systemBlocks = new JsonArray();
        systemBlocks.add(system);
        body.add("system", systemBlocks);
        if (!request.tools().isEmpty()) {
            body.add("tools", request.tools().deepCopy());
            JsonObject choice = new JsonObject();
            choice.addProperty("type", "auto");
            body.add("tool_choice", choice);
        }
        if (s.effort() != null && !s.effort().isBlank()) {
            JsonObject config = new JsonObject();
            config.addProperty("effort", s.effort());
            body.add("output_config", config);
        }
        JsonArray messages = request.messages().deepCopy();
        if (s.promptCaching() && !messages.isEmpty()) {
            JsonElement content = messages.get(messages.size() - 1).getAsJsonObject().get("content");
            if (content != null && content.isJsonArray() && !content.getAsJsonArray().isEmpty()) {
                JsonArray blocks = content.getAsJsonArray();
                blocks.get(blocks.size() - 1).getAsJsonObject().add("cache_control", ephemeral());
            }
        }
        body.add("messages", messages);
        return body;
    }

    static Reply parseReply(JsonObject json) {
        JsonElement rawContent = json.get("content");
        JsonArray content = rawContent != null && rawContent.isJsonArray() ? rawContent.getAsJsonArray() : new JsonArray();
        List<ToolCall> calls = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (JsonElement element : content) {
            if (!element.isJsonObject()) continue;
            JsonObject block = element.getAsJsonObject();
            String type = string(block, "type");
            if (type.equals("text")) {
                if (text.length() > 0) text.append('\n');
                text.append(string(block, "text"));
            } else if (type.equals("tool_use")) {
                JsonElement input = block.get("input");
                calls.add(new ToolCall(string(block, "id"), string(block, "name"),
                        input != null && input.isJsonObject() ? input.getAsJsonObject() : new JsonObject()));
            }
        }
        JsonElement rawUsage = json.get("usage");
        JsonObject usage = rawUsage != null && rawUsage.isJsonObject() ? rawUsage.getAsJsonObject() : new JsonObject();
        return new Reply(string(json, "id"), content, List.copyOf(calls), text.toString().strip(),
                string(json, "stop_reason"), new Usage(integer(usage, "input_tokens"), integer(usage, "output_tokens"),
                integer(usage, "cache_creation_input_tokens"), integer(usage, "cache_read_input_tokens")));
    }

    private JsonObject post(URI uri, JsonObject body) throws BrainException {
        String key = apiKey.get();
        if (key == null || key.isBlank()) throw new BrainException(BrainException.Kind.NO_KEY, "no API key");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(settings.requestTimeoutSeconds()))
                .header("x-api-key", key)
                .header("anthropic-version", API_VERSION)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        for (int attempt = 0; ; attempt++) {
            BrainException failure;
            long retryAfterMs = -1;
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) return JsonParser.parseString(response.body()).getAsJsonObject();
                failure = BrainException.fromResponse(response.statusCode(), response.body(),
                        response.headers().firstValue("request-id").orElse(null));
                retryAfterMs = response.headers().firstValue("retry-after").map(ApiBrain::millis).orElse(-1L);
            } catch (HttpTimeoutException e) {
                failure = new BrainException(BrainException.Kind.TIMEOUT, "timed out");
            } catch (IOException e) {
                failure = new BrainException(BrainException.Kind.NETWORK, e.getClass().getSimpleName() + ": " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BrainException(BrainException.Kind.INTERRUPTED, "interrupted");
            } catch (JsonParseException | IllegalStateException e) {
                throw new BrainException(BrainException.Kind.INTERNAL, "unreadable API response");
            }
            if (!failure.kind().retryable || attempt >= BACKOFF_MS.length) throw failure;
            long delay = retryAfterMs >= 0 ? Math.min(retryAfterMs, 10_000) : BACKOFF_MS[attempt];
            LOG.warn("API call failed ({}); retry {} in {} ms", failure.getMessage(), attempt + 1, delay);
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BrainException(BrainException.Kind.INTERRUPTED, "interrupted");
            }
        }
    }

    private static long millis(String seconds) {
        try {
            return (long) (Double.parseDouble(seconds.strip()) * 1000);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    private static int integer(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsInt() : 0;
    }

    private static JsonObject text(String text) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "text");
        o.addProperty("text", text);
        return o;
    }

    private static JsonObject ephemeral() {
        JsonObject o = new JsonObject();
        o.addProperty("type", "ephemeral");
        return o;
    }
}
