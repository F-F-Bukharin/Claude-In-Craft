package com.example.claudecodehelper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Map;

/**
 * Continuation for stateless callers. A warm session remembers the Messages-style history it has read (its
 * {@code seen}); a new request continues it when its history starts with exactly that, compared as JSON after a
 * light normalisation, and adds at least one message.
 */
public final class Messages {
    private Messages() {}

    /**
     * The comparison form of a history: string content becomes one text block, and {@code cache_control} and null
     * fields are dropped (clients move cache breakpoints forward and some SDKs echo null fields back).
     */
    public static JsonArray canonical(JsonArray messages) {
        JsonArray out = new JsonArray();
        for (JsonElement m : messages) {
            if (!m.isJsonObject()) {
                out.add(m.deepCopy());
                continue;
            }
            JsonObject msg = m.getAsJsonObject().deepCopy();
            JsonElement content = msg.get("content");
            if (content != null && content.isJsonPrimitive()) {
                JsonArray blocks = new JsonArray();
                blocks.add(Json.text(content.getAsString()));
                msg.add("content", blocks);
            }
            strip(msg);
            out.add(msg);
        }
        return out;
    }

    /** Where {@code messages} goes on from {@code seen} (both canonical), or -1 if it doesn't start with exactly that. */
    public static int continues(JsonArray seen, JsonArray messages) {
        if (seen.isEmpty() || messages.size() <= seen.size()) return -1;
        for (int i = 0; i < seen.size(); i++) {
            if (!seen.get(i).equals(messages.get(i))) return -1;
        }
        return seen.size();
    }

    private static void strip(JsonElement e) {
        if (e.isJsonArray()) {
            for (JsonElement child : e.getAsJsonArray()) strip(child);
        } else if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            o.entrySet().removeIf(entry -> entry.getKey().equals("cache_control") || entry.getValue().isJsonNull());
            for (Map.Entry<String, JsonElement> entry : o.entrySet()) strip(entry.getValue());
        }
    }
}
