package com.example.claudeplayer.core.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The plan's G1 stub brain: {@code C demo} walks a 3-block square through the real queue, executor and
 * body without calling the API, so pacing and movement can be checked before a key exists. Every other order
 * goes to {@code fallback}.
 */
public final class DemoBrain implements Brain {
    private static final Pattern DEMO_ORDER = Pattern.compile("<order from=\"[^\"]*\">\ndemo\n</order>");
    private static final Pattern POS = Pattern.compile("\"pos\":\\[(-?[\\d.]+),(-?[\\d.]+),(-?[\\d.]+)]");

    private final Brain fallback;

    public DemoBrain(Brain fallback) {
        this.fallback = fallback;
    }

    @Override
    public Reply send(Request request) throws BrainException {
        String opening = firstText(request.messages());
        if (!DEMO_ORDER.matcher(opening).find()) return fallback.send(request);
        if (request.messages().size() > 1) return text("Demo done.");
        Matcher pos = POS.matcher(opening);
        if (!pos.find()) return text("Demo: there's no body to move.");
        double x = Double.parseDouble(pos.group(1)), y = Double.parseDouble(pos.group(2)), z = Double.parseDouble(pos.group(3));
        List<ToolCall> calls = new ArrayList<>();
        calls.add(call(calls, "say", "text", "Demo: walking a small square."));
        calls.add(move(calls, x + 3, y, z));
        calls.add(move(calls, x + 3, y, z + 3));
        calls.add(move(calls, x, y, z + 3));
        calls.add(move(calls, x, y, z));
        calls.add(call(calls, "wait", "seconds", 1));
        calls.add(call(calls, "say", "text", "Back where I started."));
        JsonArray content = new JsonArray();
        for (ToolCall c : calls) {
            JsonObject block = new JsonObject();
            block.addProperty("type", "tool_use");
            block.addProperty("id", c.id());
            block.addProperty("name", c.name());
            block.add("input", c.input());
            content.add(block);
        }
        return new Reply("demo", content, List.copyOf(calls), "", "tool_use", new Usage(0, 0, 0, 0));
    }

    private static String firstText(JsonArray messages) {
        if (messages.isEmpty()) return "";
        JsonElement content = messages.get(0).getAsJsonObject().get("content");
        if (content == null || !content.isJsonArray()) return "";
        StringBuilder b = new StringBuilder();
        for (JsonElement block : content.getAsJsonArray()) {
            JsonElement text = block.getAsJsonObject().get("text");
            if (text != null) b.append(text.getAsString());
        }
        return b.toString();
    }

    private static ToolCall move(List<ToolCall> calls, double x, double y, double z) {
        JsonObject input = new JsonObject();
        input.addProperty("x", x);
        input.addProperty("y", y);
        input.addProperty("z", z);
        return new ToolCall(id(calls), "move_to", input);
    }

    private static ToolCall call(List<ToolCall> calls, String name, String key, Object value) {
        JsonObject input = new JsonObject();
        if (value instanceof Number n) input.addProperty(key, n);
        else input.addProperty(key, String.valueOf(value));
        return new ToolCall(id(calls), name, input);
    }

    private static String id(List<ToolCall> calls) {
        return String.format(Locale.ROOT, "demo_%02d", calls.size());
    }

    private static Reply text(String text) {
        JsonObject block = new JsonObject();
        block.addProperty("type", "text");
        block.addProperty("text", text);
        JsonArray content = new JsonArray();
        content.add(block);
        return new Reply("demo", content, List.of(), text, "end_turn", new Usage(0, 0, 0, 0));
    }
}
