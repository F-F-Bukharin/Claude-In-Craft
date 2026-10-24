package com.example.claudecodehelper.server;

import com.example.claudecodehelper.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Tool use, emulated: Claude Code runs with no tools of its own, so the request's tools become a JSON schema for
 * structured output ({@code {"text": ..., "tool_calls": [{"name", "input"}]}}) plus a section of the system prompt
 * describing each tool, and the structured answer becomes standard {@code tool_use} blocks.
 *
 * <p>The schema pins the tool names (an enum) but leaves each input a plain object: the full input schemas are in
 * the system prompt, where the model reads them. Claude Code's structured output is then robust to any input
 * schema a client sends.
 *
 * @param schema the {@code --json-schema}, or null when no tool may be called (tool_choice none)
 * @param section what the system prompt says about the tools
 * @param names the tools that may be called
 */
record Tools(String schema, String section, Set<String> names) {
    private static final Pattern NAME = Pattern.compile("[a-zA-Z0-9_-]{1,128}");

    /** The plan for a request's {@code tools} and {@code tool_choice}, or null when it has no tools. */
    static Tools plan(JsonElement tools, JsonElement toolChoice) throws ApiError {
        if (tools == null || tools.isJsonNull()) return null;
        if (!tools.isJsonArray()) throw ApiError.invalid("tools: must be an array");
        if (tools.getAsJsonArray().isEmpty()) return null;
        Set<String> names = new LinkedHashSet<>();
        StringBuilder list = new StringBuilder();
        JsonArray all = tools.getAsJsonArray();
        for (int i = 0; i < all.size(); i++) {
            if (!all.get(i).isJsonObject()) throw ApiError.invalid("tools." + i + ": must be an object");
            JsonObject t = all.get(i).getAsJsonObject();
            String name = Json.str(t, "name");
            String type = Json.str(t, "type");
            if (!type.isEmpty() && !type.equals("custom")) {
                throw ApiError.invalid("tools." + i + ": server tool '" + type + "' isn't supported (Claude Code runs with no tools)");
            }
            if (!NAME.matcher(name).matches()) throw ApiError.invalid("tools." + i + ".name: must match [a-zA-Z0-9_-]{1,128}");
            if (!names.add(name)) throw ApiError.invalid("tools." + i + ".name: '" + name + "' is defined twice");
            JsonElement schema = t.get("input_schema");
            if (schema != null && !schema.isJsonObject()) throw ApiError.invalid("tools." + i + ".input_schema: must be an object");
            list.append("\n\n## ").append(name);
            if (!Json.str(t, "description").isBlank()) list.append('\n').append(Json.str(t, "description").strip());
            list.append("\nInput schema: ").append(schema == null ? "{\"type\":\"object\"}" : schema.toString());
        }

        String choice = "auto";
        String forced = null;
        boolean single = false;
        if (toolChoice != null && !toolChoice.isJsonNull()) {
            if (!toolChoice.isJsonObject()) throw ApiError.invalid("tool_choice: must be an object");
            JsonObject c = toolChoice.getAsJsonObject();
            choice = Json.str(c, "type");
            single = Json.bool(c, "disable_parallel_tool_use");
            switch (choice) {
                case "auto", "any", "none" -> { }
                case "tool" -> {
                    forced = Json.str(c, "name");
                    if (!names.contains(forced)) throw ApiError.invalid("tool_choice.name: no tool named '" + forced + "'");
                }
                default -> throw ApiError.invalid("tool_choice.type: must be auto, any, tool or none");
            }
        }

        if (choice.equals("none")) {
            String section = "# Tools\nThese tools exist in this conversation, but don't call any of them now: answer in "
                    + "plain text. Earlier calls and their results appear in the messages as [tool_use] and [tool_result]."
                    + list;
            return new Tools(null, section, Set.of());
        }

        Set<String> callable = forced == null ? names : Set.of(forced);
        JsonArray nameEnum = new JsonArray();
        callable.forEach(nameEnum::add);
        JsonObject name = new JsonObject();
        name.addProperty("type", "string");
        name.add("enum", nameEnum);
        JsonObject input = new JsonObject();
        input.addProperty("type", "object");
        JsonObject callProps = new JsonObject();
        callProps.add("name", name);
        callProps.add("input", input);
        JsonObject call = new JsonObject();
        call.addProperty("type", "object");
        call.add("properties", callProps);
        call.add("required", array("name", "input"));
        JsonObject calls = new JsonObject();
        calls.addProperty("type", "array");
        calls.add("items", call);
        boolean required = choice.equals("any") || forced != null;
        if (required) calls.addProperty("minItems", 1);
        if (single) calls.addProperty("maxItems", 1);
        JsonObject text = new JsonObject();
        text.addProperty("type", "string");
        JsonObject props = new JsonObject();
        props.add("text", text);
        props.add("tool_calls", calls);
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", array("text", "tool_calls"));

        StringBuilder section = new StringBuilder("""
                # Tools
                You can't call the tools below yourself. Your only callable tool is StructuredOutput, and every reply \
                is exactly one StructuredOutput call: {"text": ..., "tool_calls": [...]}.
                - To use tools, list each call in tool_calls as {"name": <tool name>, "input": {...}}, with input \
                matching that tool's input schema. The caller runs them and sends the results back in the next turn \
                as [tool_result] entries. Put any words for the user in text, or leave it empty.
                - To answer without a tool, leave tool_calls empty and put your whole answer in text.
                - Earlier calls appear in the messages as [tool_use id=... name=...] followed by their input.""");
        if (forced != null) section.append("\n- In this reply you must call the tool ").append(forced).append('.');
        else if (required) section.append("\n- In this reply you must call at least one tool.");
        if (single) section.append("\n- Call at most one tool per reply.");
        section.append(list);
        return new Tools(schema.toString(), section.toString(), callable);
    }

    /** A structured answer as Messages API content: a text block when there is text, then one tool_use per call. */
    JsonArray content(JsonObject structured, Supplier<String> ids) {
        JsonArray content = new JsonArray();
        String text = Json.str(structured, "text").strip();
        if (!text.isEmpty()) content.add(Json.text(text));
        JsonElement calls = structured == null ? null : structured.get("tool_calls");
        if (calls != null && calls.isJsonArray()) {
            for (JsonElement e : calls.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                JsonObject c = e.getAsJsonObject();
                String name = Json.str(c, "name");
                if (!names.contains(name)) continue; // the schema's enum should prevent it; never invent a tool
                JsonElement input = c.get("input");
                JsonObject block = new JsonObject();
                block.addProperty("type", "tool_use");
                block.addProperty("id", ids.get());
                block.addProperty("name", name);
                block.add("input", input != null && input.isJsonObject() ? input.deepCopy() : new JsonObject());
                content.add(block);
            }
        }
        return content;
    }

    private static JsonArray array(String... values) {
        JsonArray a = new JsonArray();
        for (String v : values) a.add(v);
        return a;
    }
}
