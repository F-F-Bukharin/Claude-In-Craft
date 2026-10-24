package com.example.claudecodehelper.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** A Messages request rendered as one stream-json turn. */
class RenderTest {
    private static final String PNG = "{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"iVBORw0K\"}}";

    private static JsonArray messages(String json) {
        return JsonParser.parseString(json).getAsJsonArray();
    }

    private static String type(JsonArray blocks, int i) {
        return blocks.get(i).getAsJsonObject().get("type").getAsString();
    }

    private static String text(JsonArray blocks, int i) {
        return blocks.get(i).getAsJsonObject().get("text").getAsString();
    }

    @Test
    void systemIsAStringOrTextBlocks() throws ApiError {
        assertEquals("Be brief.", Render.system(JsonParser.parseString("\"Be brief.\"")));
        assertEquals("One.\n\nTwo.", Render.system(JsonParser.parseString(
                "[{\"type\":\"text\",\"text\":\"One.\",\"cache_control\":{\"type\":\"ephemeral\"}},{\"type\":\"text\",\"text\":\"Two.\"}]")));
        assertEquals("", Render.system(null));
        assertThrows(ApiError.class, () -> Render.system(JsonParser.parseString("42")));
    }

    @Test
    void aSingleUserMessageKeepsItsTextAndImagesInOrder() throws ApiError {
        JsonArray blocks = Render.turn(messages("[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"What is this?\"},"
                + PNG + ",{\"type\":\"text\",\"text\":\"Be short.\"}]}]"), 0);
        assertEquals(3, blocks.size());
        assertEquals("What is this?", text(blocks, 0));
        assertEquals("image", type(blocks, 1));
        assertEquals("iVBORw0K", blocks.get(1).getAsJsonObject().getAsJsonObject("source").get("data").getAsString());
        assertEquals("Be short.", text(blocks, 2));
    }

    @Test
    void stringContentIsOneTextBlock() throws ApiError {
        JsonArray blocks = Render.turn(messages("[{\"role\":\"user\",\"content\":\"hello\"}]"), 0);
        assertEquals(1, blocks.size());
        assertEquals("hello", text(blocks, 0));
    }

    private static final String HISTORY = "[{\"role\":\"user\",\"content\":\"Weather in Paris?\"},"
            + "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"Checking.\"},"
            + "{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"get_weather\",\"input\":{\"city\":\"Paris\"}}]},"
            + "{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_1\",\"content\":"
            + "[{\"type\":\"text\",\"text\":\"sunny, 21 C\"}," + PNG + "]},{\"type\":\"text\",\"text\":\"And tomorrow?\"}]}]";

    @Test
    void aFreshSessionReadsTheWholeHistoryWithToolsAsText() throws ApiError {
        JsonArray blocks = Render.turn(messages(HISTORY), 0);
        String first = text(blocks, 0);
        assertTrue(first.contains("[user]\nWeather in Paris?"), first);
        assertTrue(first.contains("[assistant]\nChecking."), first);
        assertTrue(first.contains("[tool_use id=toolu_1 name=get_weather]\n{\"city\":\"Paris\"}"), first);
        assertTrue(first.contains("[tool_result for get_weather id=toolu_1]\nsunny, 21 C"), first);
        assertEquals("image", type(blocks, 1), "the tool result's picture follows its text");
        assertTrue(text(blocks, 2).contains("And tomorrow?"));
    }

    @Test
    void aContinuedSessionGetsOnlyTheNewMessage() throws ApiError {
        JsonArray blocks = Render.turn(messages(HISTORY), 2);
        String first = text(blocks, 0);
        assertFalse(first.contains("Weather in Paris?"), "the session already read it");
        assertFalse(first.contains("[user]"), "a single new message needs no role header");
        assertTrue(first.contains("[tool_result for get_weather id=toolu_1]"), "the tool name comes from the earlier turn");
        assertEquals("image", type(blocks, 1));
    }

    @Test
    void errorResultsAreMarked() throws ApiError {
        JsonArray blocks = Render.turn(messages("[{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\","
                + "\"tool_use_id\":\"x\",\"is_error\":true,\"content\":\"boom\"}]}]"), 0);
        assertTrue(text(blocks, 0).contains("[tool_result for tool id=x (error)]\nboom"), text(blocks, 0));
    }

    @Test
    void unsupportedBlocksAreRejected() {
        ApiError e = assertThrows(ApiError.class, () -> Render.turn(messages("[{\"role\":\"user\",\"content\":"
                + "[{\"type\":\"document\",\"source\":{}}]}]"), 0));
        assertEquals(400, e.status);
        assertThrows(ApiError.class, () -> Render.turn(messages("[{\"role\":\"user\",\"content\":[{\"type\":\"image\","
                + "\"source\":{\"type\":\"base64\",\"media_type\":\"image/gif\",\"data\":\"R0lG\"}}]}]"), 0));
        assertThrows(ApiError.class, () -> Render.turn(messages("[{\"role\":\"user\",\"content\":[{\"type\":\"image\","
                + "\"source\":{\"type\":\"url\",\"url\":\"https://example.com/a.png\"}}]}]"), 0));
    }

    @Test
    void thinkingBlocksAreSkipped() throws ApiError {
        JsonArray blocks = Render.turn(messages("[{\"role\":\"user\",\"content\":\"q\"},{\"role\":\"assistant\",\"content\":"
                + "[{\"type\":\"thinking\",\"thinking\":\"secret plan\",\"signature\":\"s\"},{\"type\":\"text\",\"text\":\"a\"}]},"
                + "{\"role\":\"user\",\"content\":\"next\"}]"), 0);
        assertFalse(text(blocks, 0).contains("secret plan"));
        JsonObject b = blocks.get(0).getAsJsonObject();
        assertEquals("text", b.get("type").getAsString());
    }
}
