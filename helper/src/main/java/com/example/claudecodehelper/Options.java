package com.example.claudecodehelper;

import com.google.gson.JsonParser;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a session is started with. Sessions with equal options share a spare, and only a session started with the
 * same options can continue a conversation.
 *
 * @param model a model id or alias for {@code --model}, or null for Claude Code's default
 * @param effort low, medium, high, xhigh or max for {@code --effort}, or null
 * @param systemPrompt replaces Claude Code's own system prompt (it is always replaced; empty uses a short default)
 * @param jsonSchema a JSON schema for {@code --json-schema} structured output, or null for plain text
 */
public record Options(String model, String effort, String systemPrompt, String jsonSchema) {
    /** Used when no system prompt is given, so Claude Code's own coding-agent prompt never applies. */
    public static final String DEFAULT_SYSTEM = "You are Claude, a helpful assistant.";
    private static final Pattern MODEL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/@\\[\\]-]{0,99}");
    private static final Set<String> EFFORTS = Set.of("low", "medium", "high", "xhigh", "max");

    /** Validates the values that reach the command line, so none can be read as a flag. */
    public Options {
        model = blankToNull(model);
        effort = blankToNull(effort);
        systemPrompt = systemPrompt == null || systemPrompt.isBlank() ? DEFAULT_SYSTEM : systemPrompt;
        jsonSchema = blankToNull(jsonSchema);
        if (model != null && !MODEL.matcher(model).matches()) throw new IllegalArgumentException("invalid model id");
        if (effort != null && !EFFORTS.contains(effort)) {
            throw new IllegalArgumentException("effort must be one of low, medium, high, xhigh, max");
        }
        if (jsonSchema != null) {
            try {
                // compact, so the command line carries no stray whitespace
                jsonSchema = JsonParser.parseString(jsonSchema).getAsJsonObject().toString();
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("jsonSchema must be a JSON object");
            }
        }
    }

    public Options withModel(String m) {
        return new Options(m, effort, systemPrompt, jsonSchema);
    }

    public Options withEffort(String e) {
        return new Options(model, e, systemPrompt, jsonSchema);
    }

    public Options withSystemPrompt(String s) {
        return new Options(model, effort, s, jsonSchema);
    }

    public Options withJsonSchema(String schema) {
        return new Options(model, effort, systemPrompt, schema);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
