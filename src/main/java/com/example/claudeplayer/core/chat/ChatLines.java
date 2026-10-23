package com.example.claudeplayer.core.chat;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the body's chat readable whatever the model writes: at most two lines of 100 characters, split at a
 * sentence end or a space, with "..." when something was cut. The prompt asks for short lines too; this is the
 * part that holds when the model doesn't listen.
 */
public final class ChatLines {
    public static final int MAX_CHARS = 100;
    public static final int MAX_LINES = 2;
    private static final String MORE = "...";

    private ChatLines() {}

    public static List<String> fit(String text) {
        return fit(text, MAX_CHARS, MAX_LINES);
    }

    static List<String> fit(String text, int maxChars, int maxLines) {
        String rest = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        List<String> lines = new ArrayList<>();
        while (!rest.isEmpty() && lines.size() < maxLines) {
            if (rest.length() <= maxChars) {
                lines.add(rest);
                rest = "";
                break;
            }
            int cut = breakAt(rest, maxChars);
            lines.add(rest.substring(0, cut).strip());
            rest = rest.substring(cut).strip();
        }
        if (!rest.isEmpty()) {
            String last = lines.remove(lines.size() - 1);
            if (last.length() > maxChars - MORE.length()) last = last.substring(0, wordEnd(last, maxChars - MORE.length())).strip();
            lines.add(last + MORE);
        }
        return lines;
    }

    // The last sentence end in the back half of the line, else the last space, else a hard cut.
    private static int breakAt(String s, int max) {
        for (int i = max - 1; i >= max / 2; i--) {
            char c = s.charAt(i);
            if ((c == '.' || c == '!' || c == '?') && s.charAt(i + 1) == ' ') return i + 1;
        }
        return wordEnd(s, max);
    }

    private static int wordEnd(String s, int max) {
        int space = s.lastIndexOf(' ', max);
        return space > 0 ? space : max;
    }
}
