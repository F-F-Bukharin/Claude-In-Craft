package com.example.claudecodehelper.server;

import com.example.claudecodehelper.Blocks;
import com.example.claudecodehelper.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;

/**
 * A Messages API request as one Claude Code turn: the system text, and the unread messages as content blocks.
 *
 * <p>A single new user message goes over as it is: its text and images, with tool results written out as text. A
 * longer stretch (a fresh session reading an earlier conversation) becomes one transcript with {@code [user]} and
 * {@code [assistant]} headers, where the assistant's tool calls appear as
 * {@code [tool_use id=... name=...]} followed by the input JSON. Images stay image blocks wherever they occur.
 */
final class Render {
    private Render() {}

    /** {@code system} as a string or text blocks; "" when absent. */
    static String system(JsonElement system) throws ApiError {
        if (system == null || system.isJsonNull()) return "";
        if (system.isJsonPrimitive() && system.getAsJsonPrimitive().isString()) return system.getAsString();
        if (!system.isJsonArray()) throw ApiError.invalid("system: a string or an array of text blocks is required");
        StringBuilder sb = new StringBuilder();
        for (JsonElement e : system.getAsJsonArray()) {
            if (!e.isJsonObject() || !Json.str(e.getAsJsonObject(), "type").equals("text")) {
                throw ApiError.invalid("system: only text blocks are supported");
            }
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(Json.str(e.getAsJsonObject(), "text"));
        }
        return sb.toString();
    }

    /** The messages from {@code from} on as text and image blocks for one stream-json user line. */
    static JsonArray turn(JsonArray messages, int from) throws ApiError {
        Map<String, String> toolNames = new HashMap<>();
        for (JsonElement m : messages) {
            for (JsonElement b : blocks(m.getAsJsonObject())) {
                if (b.isJsonObject() && Json.str(b.getAsJsonObject(), "type").equals("tool_use")) {
                    toolNames.put(Json.str(b.getAsJsonObject(), "id"), Json.str(b.getAsJsonObject(), "name"));
                }
            }
        }
        Out out = new Out();
        boolean transcript = messages.size() - from > 1;
        if (transcript && from == 0) out.text("The conversation so far, oldest first. Reply to the last user message.");
        for (int i = from; i < messages.size(); i++) {
            JsonObject m = messages.get(i).getAsJsonObject();
            if (transcript) out.header("[" + Json.str(m, "role") + "]");
            JsonArray blocks = blocks(m);
            for (int j = 0; j < blocks.size(); j++) {
                if (!blocks.get(j).isJsonObject()) throw ApiError.invalid("messages." + i + ".content." + j + ": must be an object");
                block(blocks.get(j).getAsJsonObject(), toolNames, out, "messages." + i + ".content." + j);
            }
        }
        return out.done();
    }

    private static JsonArray blocks(JsonObject message) {
        JsonElement c = message.get("content");
        if (c != null && c.isJsonArray()) return c.getAsJsonArray();
        JsonArray one = new JsonArray();
        if (c != null && c.isJsonPrimitive()) one.add(Json.text(c.getAsString()));
        return one;
    }

    private static void block(JsonObject b, Map<String, String> toolNames, Out out, String where) throws ApiError {
        switch (Json.str(b, "type")) {
            case "text" -> out.text(Json.str(b, "text"));
            case "image" -> out.image(b, where);
            case "tool_use" -> out.text("[tool_use id=" + Json.str(b, "id") + " name=" + Json.str(b, "name") + "]\n"
                    + (b.has("input") ? b.get("input").toString() : "{}"));
            case "tool_result" -> {
                String id = Json.str(b, "tool_use_id");
                StringBuilder head = new StringBuilder("[tool_result for ").append(toolNames.getOrDefault(id, "tool"))
                        .append(" id=").append(id).append(Json.bool(b, "is_error") ? " (error)" : "").append("]");
                JsonElement c = b.get("content");
                if (c == null || c.isJsonNull()) {
                    out.text(head.toString());
                } else if (c.isJsonPrimitive()) {
                    out.text(head + "\n" + c.getAsString());
                } else if (c.isJsonArray()) {
                    StringBuilder text = new StringBuilder(head);
                    JsonArray parts = c.getAsJsonArray();
                    for (JsonElement p : parts) {
                        if (p.isJsonObject() && Json.str(p.getAsJsonObject(), "type").equals("text")) {
                            text.append('\n').append(Json.str(p.getAsJsonObject(), "text"));
                        }
                    }
                    out.text(text.toString());
                    for (int k = 0; k < parts.size(); k++) {
                        if (!parts.get(k).isJsonObject()) continue;
                        String t = Json.str(parts.get(k).getAsJsonObject(), "type");
                        if (t.equals("image")) out.image(parts.get(k).getAsJsonObject(), where + ".content." + k);
                        else if (!t.equals("text")) throw ApiError.invalid(where + ".content." + k + ": unsupported block type '" + t + "'");
                    }
                } else {
                    throw ApiError.invalid(where + ".content: a string or an array of blocks is required");
                }
            }
            case "thinking", "redacted_thinking" -> { } // Claude Code thinks on its own; earlier thinking isn't replayed
            default -> throw ApiError.invalid(where + ": unsupported content block type '" + Json.str(b, "type") + "'");
        }
    }

    /** Collects text pieces into text blocks, split where an image goes. */
    private static final class Out {
        private final JsonArray blocks = new JsonArray();
        private final StringBuilder text = new StringBuilder();
        private boolean afterHeader;

        /** A role line: the message's first piece follows it on the next line. */
        void header(String h) {
            text(h);
            afterHeader = true;
        }

        void text(String s) {
            if (text.length() > 0) text.append(afterHeader ? "\n" : "\n\n");
            afterHeader = false;
            text.append(s);
        }

        void image(JsonObject b, String where) throws ApiError {
            try {
                Blocks.check(b);
            } catch (IllegalArgumentException e) {
                throw ApiError.invalid(where + ": " + e.getMessage());
            }
            flush();
            afterHeader = false;
            JsonObject source = b.getAsJsonObject("source");
            blocks.add(Blocks.image(Json.str(source, "media_type"), Json.str(source, "data")));
        }

        private void flush() {
            if (text.length() > 0) blocks.add(Json.text(text.toString()));
            text.setLength(0);
        }

        JsonArray done() {
            flush();
            if (blocks.isEmpty()) blocks.add(Json.text("(empty message)"));
            return blocks;
        }
    }
}
