package com.example.claudeplayer.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChatLinesTest {
    @Test
    void shortTextIsOneLine() {
        assertEquals(List.of("On my way."), ChatLines.fit("  On my\n way. "));
        assertEquals(List.of(), ChatLines.fit("   "));
    }

    @Test
    void longTextSplitsAtASentenceEnd() {
        String first = "I found the chest by the river and it has twenty logs in it.";
        String second = "Heading back home now with all of them.";
        List<String> lines = ChatLines.fit(first + " " + second, 70, 2);
        assertEquals(List.of(first, second), lines);
    }

    @Test
    void withoutASentenceEndItSplitsAtASpace() {
        List<String> lines = ChatLines.fit("one two three four five six seven", 12, 3);
        assertEquals(List.of("one two", "three four", "five six..."), lines);
        lines.forEach(l -> assertTrue(l.length() <= 12, l));
    }

    @Test
    void anythingPastTwoLinesIsCutWithAnEllipsis() {
        String rambling = "word ".repeat(200);
        List<String> lines = ChatLines.fit(rambling);
        assertEquals(ChatLines.MAX_LINES, lines.size());
        lines.forEach(l -> assertTrue(l.length() <= ChatLines.MAX_CHARS, l));
        assertTrue(lines.get(1).endsWith("..."));
    }

    @Test
    void oneHugeWordIsHardCut() {
        List<String> lines = ChatLines.fit("x".repeat(250), 100, 2);
        assertEquals(100, lines.get(0).length());
        assertTrue(lines.get(1).length() <= 100 && lines.get(1).endsWith("..."));
    }
}
