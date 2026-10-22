package com.example.claudeplayer.core.chat;

import com.example.claudeplayer.core.safety.WorldText;
import java.util.ArrayDeque;
import java.util.List;

/** The last few relevant chat lines: allowlisted players and the agent's own replies. Server thread only. */
public final class Conversation {
    public record Line(String speaker, String text) {}

    private final int capacity;
    private final ArrayDeque<Line> lines = new ArrayDeque<>();

    public Conversation(int capacity) {
        this.capacity = capacity;
    }

    public void add(String speaker, String text) {
        lines.addLast(new Line(WorldText.clean(speaker, WorldText.NAME_MAX), WorldText.clean(text, WorldText.TEXT_MAX)));
        while (lines.size() > capacity) lines.removeFirst();
    }

    public List<Line> recent() {
        return List.copyOf(lines);
    }
}
