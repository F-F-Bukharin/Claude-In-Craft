package com.example.claudecodehelper;

/** A turn that didn't produce an answer, classified so callers can map it (the server maps it to API errors). */
public final class ClaudeCodeException extends Exception {
    public enum Kind {
        /** No {@code claude} executable found, or it couldn't start. */
        NO_CLI,
        /** Claude Code isn't signed in. */
        NOT_SIGNED_IN,
        /** Claude Code failed or exited for another reason. */
        CLI,
        /** No answer within the turn timeout (the write of the turn counts too). */
        TIMEOUT,
        /** The session was stopped (close, cancel, idle stop) or the library is shutting down. */
        CANCELLED,
        /** Every session slot is busy. */
        BUSY,
        /** The plan's usage limit or a rate limit (api_error_status 429). */
        RATE_LIMITED,
        /** Anthropic's API is overloaded (529). */
        OVERLOADED,
        /** Another API-side failure (5xx). */
        SERVER,
        /** Output Claude Code shouldn't have produced: a missing structured output, an oversized line. */
        BAD_OUTPUT
    }

    private final Kind kind;
    private final int status;

    public ClaudeCodeException(Kind kind, String message) {
        this(kind, 0, message);
    }

    public ClaudeCodeException(Kind kind, int status, String message) {
        super(message);
        this.kind = kind;
        this.status = status;
    }

    public Kind kind() {
        return kind;
    }

    /** The API error status Claude Code reported, or 0. */
    public int status() {
        return status;
    }
}
