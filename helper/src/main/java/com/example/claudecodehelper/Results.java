package com.example.claudecodehelper;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Locale;

/** A turn's {@code result} event as a {@link Reply}, or the right {@link ClaudeCodeException}. */
final class Results {
    private Results() {}

    static Reply reply(JsonObject result, JsonObject rateLimit, String model, boolean schema, long millis)
            throws ClaudeCodeException {
        boolean failed = Json.bool(result, "is_error") || !Json.str(result, "subtype").equals("success");
        if (failed) {
            int status = Json.integer(result, "api_error_status");
            String text = Json.str(result, "result").isBlank() ? Json.str(result, "subtype") : Json.str(result, "result");
            String lower = text.toLowerCase(Locale.ROOT);
            ClaudeCodeException.Kind kind;
            if (lower.contains("not logged in") || lower.contains("/login")) kind = ClaudeCodeException.Kind.NOT_SIGNED_IN;
            else if (status == 429) kind = ClaudeCodeException.Kind.RATE_LIMITED;
            else if (status == 529) kind = ClaudeCodeException.Kind.OVERLOADED;
            else if (status >= 500) kind = ClaudeCodeException.Kind.SERVER;
            else kind = ClaudeCodeException.Kind.CLI;
            throw new ClaudeCodeException(kind, status, text.isBlank() ? "Claude Code reported an error" : Json.clip(text));
        }
        JsonElement structured = result.get("structured_output");
        JsonObject answer = structured != null && structured.isJsonObject() ? structured.getAsJsonObject() : null;
        if (schema && answer == null) {
            throw new ClaudeCodeException(ClaudeCodeException.Kind.BAD_OUTPUT, "Claude Code returned no structured output");
        }
        return new Reply(Json.str(result, "result"), answer, Usage.of(Json.obj(result, "usage")), rateLimit,
                Json.str(result, "stop_reason").isEmpty() ? "end_turn" : Json.str(result, "stop_reason"), model,
                Json.str(result, "session_id"), millis);
    }
}
