package com.example.claudecodehelper;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

/**
 * Content blocks a turn can carry, in the Messages API's shape: {@code {"type":"text","text":...}} and
 * {@code {"type":"image","source":{"type":"base64","media_type":"image/png","data":...}}}. Also keeps image data
 * out of anything that gets logged.
 */
public final class Blocks {
    /** The image types Claude Code is sent. */
    public static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg");

    private Blocks() {}

    public static JsonObject text(String text) {
        return Json.text(text);
    }

    /** A base64 image block; {@code mediaType} is image/png or image/jpeg. */
    public static JsonObject image(String mediaType, String base64) {
        JsonObject source = new JsonObject();
        source.addProperty("type", "base64");
        source.addProperty("media_type", mediaType);
        source.addProperty("data", base64);
        JsonObject b = new JsonObject();
        b.addProperty("type", "image");
        b.add("source", source);
        check(b);
        return b;
    }

    public static JsonObject png(byte[] bytes) {
        return image("image/png", Base64.getEncoder().encodeToString(bytes));
    }

    public static JsonObject jpeg(byte[] bytes) {
        return image("image/jpeg", Base64.getEncoder().encodeToString(bytes));
    }

    public static boolean isImage(JsonElement e) {
        return e != null && e.isJsonObject() && "image".equals(Json.str(e.getAsJsonObject(), "type"));
    }

    /** Throws IllegalArgumentException unless {@code block} is a text block or a base64 PNG/JPEG image block. */
    public static void check(JsonObject block) {
        String type = Json.str(block, "type");
        if (type.equals("text")) {
            if (!block.has("text") || !block.get("text").isJsonPrimitive()) {
                throw new IllegalArgumentException("a text block needs a text string");
            }
            return;
        }
        if (!type.equals("image")) throw new IllegalArgumentException("unsupported block type '" + type + "'");
        JsonObject source = Json.obj(block, "source");
        if (source == null || !Json.str(source, "type").equals("base64")) {
            throw new IllegalArgumentException("only base64 image sources are supported");
        }
        if (!IMAGE_TYPES.contains(Json.str(source, "media_type"))) {
            throw new IllegalArgumentException("unsupported image media_type '" + Json.str(source, "media_type")
                    + "' (use image/png or image/jpeg)");
        }
        if (Json.str(source, "data").isEmpty()) throw new IllegalArgumentException("the image has no data");
    }

    /** A copy for logging: every image's base64 {@code data} becomes {@code <image NNNN bytes>}. */
    public static JsonElement redacted(JsonElement e) {
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
                source.addProperty("data", "<image " + bytes + " bytes>");
            }
            for (Map.Entry<String, JsonElement> entry : o.entrySet()) redactInPlace(entry.getValue());
        }
    }
}
