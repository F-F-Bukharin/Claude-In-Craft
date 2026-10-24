package com.example.claudecodehelper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** The prefix match behind continuation. */
class MessagesTest {
    private static JsonArray a(String json) {
        return Messages.canonical(JsonParser.parseString(json).getAsJsonArray());
    }

    @Test
    void stringContentIsOneTextBlock() {
        assertEquals(a("[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}]"),
                a("[{\"role\":\"user\",\"content\":\"hi\"}]"));
    }

    @Test
    void cacheControlAndNullsDontMatter() {
        assertEquals(a("[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}]"),
                a("[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hi\",\"citations\":null,"
                        + "\"cache_control\":{\"type\":\"ephemeral\"}}]}]"));
    }

    @Test
    void continuesOnlyAStrictExtension() {
        JsonArray seen = a("[{\"role\":\"user\",\"content\":\"a\"},{\"role\":\"assistant\",\"content\":\"b\"}]");
        assertEquals(2, Messages.continues(seen, a("[{\"role\":\"user\",\"content\":\"a\"},{\"role\":\"assistant\","
                + "\"content\":\"b\"},{\"role\":\"user\",\"content\":\"c\"}]")));
        assertEquals(-1, Messages.continues(seen, seen), "nothing new to send");
        assertEquals(-1, Messages.continues(seen, a("[{\"role\":\"user\",\"content\":\"a\"},{\"role\":\"assistant\","
                + "\"content\":\"B\"},{\"role\":\"user\",\"content\":\"c\"}]")), "an edited reply");
        assertEquals(-1, Messages.continues(new JsonArray(), a("[{\"role\":\"user\",\"content\":\"a\"}]")));
    }

    @Test
    void toolInputsCompareDeeplyWhateverTheKeyOrder() {
        JsonArray seen = a("[{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"f\","
                + "\"input\":{\"x\":1,\"y\":[1,2]}}]}]");
        JsonArray next = a("[{\"role\":\"assistant\",\"content\":[{\"input\":{\"y\":[1,2],\"x\":1},\"name\":\"f\","
                + "\"type\":\"tool_use\",\"id\":\"t1\"}]},{\"role\":\"user\",\"content\":\"go\"}]");
        assertEquals(1, Messages.continues(seen, next));
    }
}
