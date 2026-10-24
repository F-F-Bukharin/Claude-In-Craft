package com.example.claudecodehelper.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * {@code stream: true}: the finished reply replayed as the standard event sequence (message_start, one
 * start/delta/stop per content block, message_delta, message_stop). Nothing streams while the model thinks; the
 * events arrive together once the turn is done, so streaming clients work, only without the typing effect.
 */
final class Sse {
    private Sse() {}

    static String events(JsonObject message) {
        StringBuilder sb = new StringBuilder();
        JsonObject start = message.deepCopy();
        start.add("content", new JsonArray());
        start.add("stop_reason", null);
        start.add("stop_sequence", null);
        JsonObject usage = message.getAsJsonObject("usage").deepCopy();
        usage.addProperty("output_tokens", 0);
        start.add("usage", usage);
        JsonObject ms = new JsonObject();
        ms.addProperty("type", "message_start");
        ms.add("message", start);
        event(sb, "message_start", ms);

        JsonArray content = message.getAsJsonArray("content");
        for (int i = 0; i < content.size(); i++) {
            JsonObject block = content.get(i).getAsJsonObject();
            boolean tool = block.get("type").getAsString().equals("tool_use");
            JsonObject empty = block.deepCopy();
            JsonObject delta = new JsonObject();
            if (tool) {
                empty.add("input", new JsonObject());
                delta.addProperty("type", "input_json_delta");
                delta.addProperty("partial_json", block.get("input").toString());
            } else {
                empty.addProperty("text", "");
                delta.addProperty("type", "text_delta");
                delta.addProperty("text", block.get("text").getAsString());
            }
            JsonObject cbStart = new JsonObject();
            cbStart.addProperty("type", "content_block_start");
            cbStart.addProperty("index", i);
            cbStart.add("content_block", empty);
            event(sb, "content_block_start", cbStart);
            JsonObject cbDelta = new JsonObject();
            cbDelta.addProperty("type", "content_block_delta");
            cbDelta.addProperty("index", i);
            cbDelta.add("delta", delta);
            event(sb, "content_block_delta", cbDelta);
            JsonObject cbStop = new JsonObject();
            cbStop.addProperty("type", "content_block_stop");
            cbStop.addProperty("index", i);
            event(sb, "content_block_stop", cbStop);
        }

        JsonObject md = new JsonObject();
        md.addProperty("type", "message_delta");
        JsonObject d = new JsonObject();
        d.add("stop_reason", message.get("stop_reason"));
        d.add("stop_sequence", null);
        md.add("delta", d);
        JsonObject outUsage = new JsonObject();
        JsonElement out = message.getAsJsonObject("usage").get("output_tokens");
        outUsage.add("output_tokens", out);
        md.add("usage", outUsage);
        event(sb, "message_delta", md);
        JsonObject stop = new JsonObject();
        stop.addProperty("type", "message_stop");
        event(sb, "message_stop", stop);
        return sb.toString();
    }

    private static void event(StringBuilder sb, String name, JsonObject data) {
        sb.append("event: ").append(name).append('\n').append("data: ")
                .append(com.example.claudecodehelper.server.HelperServer.GSON.toJson(data)).append("\n\n");
    }
}
