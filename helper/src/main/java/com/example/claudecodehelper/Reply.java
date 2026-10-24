package com.example.claudecodehelper;

import com.google.gson.JsonObject;

/**
 * One turn's answer.
 *
 * @param text the reply text (Claude Code's {@code result}); with a JSON schema it may be empty or the JSON itself
 * @param structured the schema-shaped answer ({@code structured_output}) when a schema is set, else null
 * @param usage token counts for this turn
 * @param rateLimit the {@code rate_limit_info} of a {@code rate_limit_event} that arrived during this turn, else null.
 *     Claude Code sends one on a session's first turn only; {@link ClaudeCode#lastRateLimit()} keeps the latest.
 * @param stopReason Claude Code's stop reason, e.g. end_turn or max_tokens
 * @param model the model that answered, when Claude Code said
 * @param sessionId Claude Code's session id
 * @param millis the turn's wall time
 */
public record Reply(String text, JsonObject structured, Usage usage, JsonObject rateLimit, String stopReason, String model,
                    String sessionId, long millis) {
}
