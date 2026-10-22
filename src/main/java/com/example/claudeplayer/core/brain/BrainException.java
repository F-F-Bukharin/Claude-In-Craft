package com.example.claudeplayer.core.brain;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** A failed model call, classified so the loop knows what to tell the player and whether a retry makes sense. */
public final class BrainException extends Exception {
    public enum Kind {
        NO_KEY(false), AUTH(false), PERMISSION(false), BAD_REQUEST(false), NOT_FOUND(false), TOO_LARGE(false),
        RATE_LIMITED(true), OVERLOADED(true), SERVER(true), TIMEOUT(true), NETWORK(true),
        INTERRUPTED(false), INTERNAL(false),
        // The Claude Code backend: the CLI is missing, or it ran and failed.
        NO_CLI(false), CLI(false);

        public final boolean retryable;

        Kind(boolean retryable) {
            this.retryable = retryable;
        }
    }

    private final Kind kind;
    private final int status;
    private final String requestId;

    public BrainException(Kind kind, int status, String message, String requestId) {
        super(message);
        this.kind = kind;
        this.status = status;
        this.requestId = requestId;
    }

    public BrainException(Kind kind, String message) {
        this(kind, 0, message, null);
    }

    /** Classifies a non-200 response, keeping the API's own error message when it sent one. */
    public static BrainException fromResponse(int status, String body, String requestId) {
        Kind kind = switch (status) {
            case 401 -> Kind.AUTH;
            case 402, 403 -> Kind.PERMISSION;
            case 404 -> Kind.NOT_FOUND;
            case 413 -> Kind.TOO_LARGE;
            case 429 -> Kind.RATE_LIMITED;
            case 529 -> Kind.OVERLOADED;
            default -> status >= 500 ? Kind.SERVER : Kind.BAD_REQUEST;
        };
        return new BrainException(kind, status, errorMessage(body, status), requestId);
    }

    public Kind kind() {
        return kind;
    }

    public int status() {
        return status;
    }

    public String requestId() {
        return requestId;
    }

    /** One line for chat. Never contains the key. */
    public String chatMessage(String apiKeyEnv) {
        return switch (kind) {
            case NO_KEY -> "No API key. Set the " + apiKeyEnv + " environment variable, or put the key in config/claudeplayer.key.";
            case AUTH -> "The API key was rejected (401). Check " + apiKeyEnv + ". Model calls are halted until the next order.";
            case PERMISSION -> "The API refused access (" + status + "): " + getMessage();
            case NOT_FOUND -> "The API doesn't know that model (404): " + getMessage();
            case BAD_REQUEST, TOO_LARGE -> "The API rejected the request (" + status + "): " + getMessage();
            case RATE_LIMITED, OVERLOADED, SERVER, TIMEOUT, NETWORK ->
                    "The API isn't answering (" + getMessage() + "). Try again in a moment.";
            case INTERRUPTED, INTERNAL -> "Internal error talking to the API: " + getMessage();
            case NO_CLI -> "Claude Code wasn't found (" + getMessage() + "). Install it and log in once, or set claude_code_path.";
            case CLI -> "Claude Code failed: " + getMessage();
        };
    }

    private static String errorMessage(String body, int status) {
        try {
            JsonObject error = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("error");
            if (error != null && error.has("message")) return error.get("message").getAsString();
        } catch (RuntimeException ignored) {
            // not the API's JSON error shape; fall back to the status
        }
        return "HTTP " + status;
    }
}
