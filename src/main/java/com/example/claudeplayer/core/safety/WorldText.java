package com.example.claudeplayer.core.safety;

import com.google.gson.JsonPrimitive;

/**
 * Cleans text that comes from the world (player and mob names, signs, books, chat) before it reaches a prompt.
 * That text is data, never instructions: it loses formatting codes and invisible characters, stays on one line,
 * gets capped, and has its angle brackets swapped so it can't close or fake the prompt's tags. The policy is
 * enforced in code anyway; this is defense in depth.
 */
public final class WorldText {
    public static final int NAME_MAX = 32;
    public static final int TEXT_MAX = 160;
    public static final int ORDER_MAX = 500;

    private static final int SECTION = '§'; // Minecraft formatting prefix
    private static final char ELLIPSIS = '…';

    private WorldText() {}

    public static String clean(String raw, int maxLen) {
        if (raw == null) return "";
        StringBuilder out = new StringBuilder(Math.min(raw.length(), maxLen + 1));
        boolean gap = false;
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == SECTION) {
                if (i < raw.length()) i += Character.charCount(raw.codePointAt(i)); // drop the code letter too
                continue;
            }
            if (Character.getType(cp) == Character.FORMAT) continue; // zero-width and bidi controls
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp) || Character.isISOControl(cp)) {
                gap = out.length() > 0;
                continue;
            }
            if (gap) {
                out.append(' ');
                gap = false;
            }
            out.appendCodePoint(cp == '<' ? '‹' : cp == '>' ? '›' : cp);
        }
        if (out.length() > maxLen) {
            int cut = Math.max(0, maxLen - 1);
            if (cut > 0 && Character.isHighSurrogate(out.charAt(cut - 1))) cut--;
            out.setLength(cut);
            out.append(ELLIPSIS);
        }
        return out.toString();
    }

    /** {@link #clean}, then quoted as a JSON string. */
    public static String quote(String raw, int maxLen) {
        return new JsonPrimitive(clean(raw, maxLen)).toString();
    }
}
