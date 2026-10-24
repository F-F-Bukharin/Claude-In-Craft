package com.example.claudecodehelper;

import com.google.gson.JsonObject;

/** Token counts from a turn's {@code result} event. */
public record Usage(int input, int output, int cacheWrite, int cacheRead) {
    public static final Usage NONE = new Usage(0, 0, 0, 0);

    static Usage of(JsonObject usage) {
        if (usage == null) return NONE;
        return new Usage(Json.integer(usage, "input_tokens"), Json.integer(usage, "output_tokens"),
                Json.integer(usage, "cache_creation_input_tokens"), Json.integer(usage, "cache_read_input_tokens"));
    }

    /** The Messages API's usage object. */
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("input_tokens", input);
        o.addProperty("output_tokens", output);
        o.addProperty("cache_creation_input_tokens", cacheWrite);
        o.addProperty("cache_read_input_tokens", cacheRead);
        return o;
    }
}
