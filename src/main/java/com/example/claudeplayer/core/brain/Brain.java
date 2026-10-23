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

    /** {@code messages} is a Messages API transcript; the loop hands each call its own copy. */
    record Request(String system, JsonArray tools, JsonArray messages) {}

    record ToolCall(String id, String name, JsonObject input) {}

    record Usage(int input, int output, int cacheWrite, int cacheRead) {
        public long total() {
            return (long) input + output + cacheWrite + cacheRead;
        }
    }

    /** {@code content} is the raw assistant content; it goes back into the transcript unchanged (thinking included). */
    record Reply(String id, JsonArray content, List<ToolCall> toolCalls, String text, String stopReason, Usage usage) {}
}
