package com.example.claudecodehelper.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Tools emulated through structured output: from the request's tools to a schema, and from the answer to tool_use. */
class ToolsTest {
    private static final String TOOLS = "[{\"name\":\"get_weather\",\"description\":\"Current weather for a city.\","
            + "\"input_schema\":{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}},"
            + "{\"name\":\"get_time\",\"description\":\"The local time.\",\"input_schema\":{\"type\":\"object\",\"properties\":{}}}]";

    private static JsonElement j(String s) {
        return JsonParser.parseString(s);
    }

    private static JsonObject callItems(Tools t) {
        return JsonParser.parseString(t.schema()).getAsJsonObject().getAsJsonObject("properties").getAsJsonObject("tool_calls");
    }

    @Test
    void noToolsNoPlan() throws ApiError {
        assertNull(Tools.plan(null, null));
        assertNull(Tools.plan(j("[]"), null));
    }

    @Test
    void theSchemaNamesEachToolAndTheSectionDescribesThem() throws ApiError {
        Tools t = Tools.plan(j(TOOLS), null);
        JsonObject schema = JsonParser.parseString(t.schema()).getAsJsonObject();
        assertEquals("[\"text\",\"tool_calls\"]", schema.get("required").toString());
        JsonObject calls = callItems(t);
        assertEquals("[\"get_weather\",\"get_time\"]",
                calls.getAsJsonObject("items").getAsJsonObject("properties").getAsJsonObject("name").get("enum").toString());
        assertFalse(calls.has("minItems"), "auto: calling a tool is optional");
        assertTrue(t.section().contains("## get_weather\nCurrent weather for a city."), t.section());
        assertTrue(t.section().contains("{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}"));
        assertTrue(t.section().contains("StructuredOutput"), "the model is told its only real tool");
    }

    @Test
    void toolChoiceShapesTheSchema() throws ApiError {
        assertEquals(1, callItems(Tools.plan(j(TOOLS), j("{\"type\":\"any\"}"))).get("minItems").getAsInt());
        Tools forced = Tools.plan(j(TOOLS), j("{\"type\":\"tool\",\"name\":\"get_time\"}"));
        assertEquals("[\"get_time\"]", callItems(forced).getAsJsonObject("items").getAsJsonObject("properties")
                .getAsJsonObject("name").get("enum").toString());
        assertEquals(1, callItems(forced).get("minItems").getAsInt());
        assertEquals(1, callItems(Tools.plan(j(TOOLS), j("{\"type\":\"auto\",\"disable_parallel_tool_use\":true}")))
                .get("maxItems").getAsInt());
        Tools none = Tools.plan(j(TOOLS), j("{\"type\":\"none\"}"));
        assertNull(none.schema(), "tool_choice none: a plain text reply");
        assertTrue(none.section().contains("get_weather"));
    }

    @Test
    void badToolsAreRejected() {
        assertThrows(ApiError.class, () -> Tools.plan(j("[{\"name\":\"bad name\",\"input_schema\":{\"type\":\"object\"}}]"), null));
        assertThrows(ApiError.class, () -> Tools.plan(j("[{\"type\":\"web_search_20250305\",\"name\":\"web_search\"}]"), null),
                "server tools can't be emulated");
        assertThrows(ApiError.class, () -> Tools.plan(j(TOOLS), j("{\"type\":\"tool\",\"name\":\"nope\"}")));
        assertThrows(ApiError.class, () -> Tools.plan(j("{}"), null));
    }

    @Test
    void theAnswerBecomesToolUseBlocksWithFreshIds() throws ApiError {
        Tools t = Tools.plan(j(TOOLS), null);
        AtomicInteger n = new AtomicInteger();
        JsonArray content = t.content(j("{\"text\":\"Let me check.\",\"tool_calls\":[{\"name\":\"get_weather\","
                + "\"input\":{\"city\":\"Paris\"}},{\"name\":\"made_up\",\"input\":{}},{\"name\":\"get_time\",\"input\":null}]}")
                .getAsJsonObject(), () -> "toolu_" + n.incrementAndGet());
        assertEquals(3, content.size(), "text, get_weather, get_time; the unknown tool is dropped");
        assertEquals("{\"type\":\"text\",\"text\":\"Let me check.\"}", content.get(0).toString());
        JsonObject call = content.get(1).getAsJsonObject();
        assertEquals("tool_use", call.get("type").getAsString());
        assertEquals("toolu_1", call.get("id").getAsString());
        assertEquals("get_weather", call.get("name").getAsString());
        assertEquals("{\"city\":\"Paris\"}", call.get("input").toString());
        assertEquals("{}", content.get(2).getAsJsonObject().get("input").toString());
        assertNotEquals(call.get("id"), content.get(2).getAsJsonObject().get("id"));
    }

    @Test
    void aPlainAnswerIsJustText() throws ApiError {
        JsonArray content = Tools.plan(j(TOOLS), null).content(j("{\"text\":\"  Hi.  \",\"tool_calls\":[]}").getAsJsonObject(),
                () -> "x");
        assertEquals("[{\"type\":\"text\",\"text\":\"Hi.\"}]", content.toString());
    }
}
