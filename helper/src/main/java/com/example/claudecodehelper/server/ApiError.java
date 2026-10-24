package com.example.claudecodehelper.server;

import com.example.claudecodehelper.ClaudeCodeException;
import com.google.gson.JsonObject;

/** A failure in the Messages API's error shape: {@code {"type":"error","error":{"type":...,"message":...}}}. */
final class ApiError extends Exception {
    final int status;
    final String type;

    ApiError(int status, String type, String message) {
        super(message);
        this.status = status;
        this.type = type;
    }

    static ApiError invalid(String message) {
        return new ApiError(400, "invalid_request_error", message);
    }

    JsonObject toJson() {
        JsonObject error = new JsonObject();
        error.addProperty("type", type);
        error.addProperty("message", getMessage());
        JsonObject o = new JsonObject();
        o.addProperty("type", "error");
        o.add("error", error);
        return o;
    }

    /** How a failed turn reaches the client. SDKs retry 429, 529 and 5xx on their own. */
    static ApiError from(ClaudeCodeException e) {
        return switch (e.kind()) {
            case BUSY -> new ApiError(529, "overloaded_error", e.getMessage());
            case OVERLOADED -> new ApiError(529, "overloaded_error", "Anthropic is overloaded: " + e.getMessage());
            case RATE_LIMITED -> new ApiError(429, "rate_limit_error", "Claude Code limit: " + e.getMessage());
            case TIMEOUT -> new ApiError(504, "timeout_error", "Claude Code gave " + e.getMessage());
            case NOT_SIGNED_IN -> new ApiError(401, "authentication_error",
                    "Claude Code isn't signed in. Run claude and sign in, then retry. (" + e.getMessage() + ")");
            case NO_CLI -> new ApiError(500, "api_error", e.getMessage());
            case SERVER -> new ApiError(502, "api_error", "Anthropic API error: " + e.getMessage());
            case CANCELLED -> new ApiError(503, "api_error", "the session was stopped: " + e.getMessage());
            case CLI, BAD_OUTPUT -> new ApiError(500, "api_error", "Claude Code failed: " + e.getMessage());
        };
    }
}
