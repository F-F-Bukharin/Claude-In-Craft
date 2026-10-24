package com.example.claudeplayer.core.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;

/**
 * The model behind the agent. {@link ApiBrain} calls the Messages API; a Claude Code backend (plan option B)
 * can implement this later without touching the loop.
 */
public interface Brain {
    /** Blocking. Background threads only, never the server thread. */
    Reply send(Request request) throws BrainException;

    /** The order was dropped: release whatever the backend keeps for it. Any thread; must not block. */
    default void reset() {}

    /**
     * {@code messages} is a Messages API transcript; the loop hands each call its own copy. {@code caveman} is true
     * for an order that started with the caveman rules on: a backend that can adds them to its system prompt, and the
     * value stays the same for every turn of the order. {@code effort} is a one-off effort for a think-harder order,
     * or null for the backend's configured one; {@link ApiBrain} ignores it.
     */
    record Request(String system, JsonArray tools, JsonArray messages, boolean caveman, String effort) {
        public Request(String system, JsonArray tools, JsonArray messages) {
            this(system, tools, messages, false, null);
        }
    }

    record ToolCall(String id, String name, JsonObject input) {}

    record Usage(int input, int output, int cacheWrite, int cacheRead) {
        public long total() {
            return (long) input + output + cacheWrite + cacheRead;
        }

        /** What the session token cap counts: cache reads cost about a tenth of other tokens. */
        public long weighted() {
            return (long) input + output + cacheWrite + cacheRead / 10;
        }
    }

    /**
     * {@code content} is the raw assistant content; it goes back into the transcript unchanged (thinking included).
     * {@code done} means the model says these calls finish the order, so no results need to go back.
     * {@code plan} is one short line on what the model is doing and why, for the Brain panel only: never in chat and
     * never in the transcript. Null when the backend gives none (the API brain's text is its chat reply).
     */
    record Reply(String id, JsonArray content, List<ToolCall> toolCalls, String text, String stopReason, Usage usage,
                 boolean done, String plan) {
        public Reply(String id, JsonArray content, List<ToolCall> toolCalls, String text, String stopReason, Usage usage) {
            this(id, content, toolCalls, text, stopReason, usage, false, null);
        }

        /** A reply without a plan. */
        public Reply(String id, JsonArray content, List<ToolCall> toolCalls, String text, String stopReason, Usage usage,
                     boolean done) {
            this(id, content, toolCalls, text, stopReason, usage, done, null);
        }

        /** A reply that isn't marked done. */
        public Reply(String id, JsonArray content, List<ToolCall> toolCalls, String text, String stopReason, Usage usage,
                     String plan) {
            this(id, content, toolCalls, text, stopReason, usage, false, plan);
        }
    }
}
