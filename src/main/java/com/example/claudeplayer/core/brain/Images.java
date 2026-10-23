package com.example.claudeplayer.core.brain;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Base64;
import java.util.Map;

/** Messages API image blocks: built from a glance's PNG, and kept out of logs. */
final class Images {
    private Images() {}

    /** {@code {"type":"image","source":{"type":"base64","media_type":"image/png","data":...}}}. */
    static JsonObject block(byte[] png) {
        JsonObject source = new JsonObject();
        source.addProperty("type", "base64");
        source.addProperty("media_type", "image/png");
        source.addProperty("data", Base64.getEncoder().encodeToString(png));
        JsonObject b = new JsonObject();
        b.addProperty("type", "image");
        b.add("source", source);
        return b;
    }

    static boolean isImage(JsonElement e) {
        return e.isJsonObject() && e.getAsJsonObject().has("type") && e.getAsJsonObject().get("type").isJsonPrimitive()
                && "image".equals(e.getAsJsonObject().get("type").getAsString());
    }

    /** A copy for logging: every image's base64 {@code data} becomes {@code <png NNNN bytes>}. */
    static JsonElement redacted(JsonElement e) {
        JsonElement copy = e.deepCopy();
        redactInPlace(copy);
        return copy;
    }

    private static void redactInPlace(JsonElement e) {
        if (e.isJsonArray()) {
            for (JsonElement child : e.getAsJsonArray()) redactInPlace(child);
        } else if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (isImage(o) && o.get("source") instanceof JsonObject source && source.get("data") != null
                    && source.get("data").isJsonPrimitive()) {
                String data = source.get("data").getAsString();
                int bytes = data.length() / 4 * 3 - (data.endsWith("==") ? 2 : data.endsWith("=") ? 1 : 0);
                source.addProperty("data", "<png " + bytes + " bytes>");
            }
            for (Map.Entry<String, JsonElement> entry : o.entrySet()) redactInPlace(entry.getValue());
        }
    }
}
