package com.example.claudeplayer.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SettingsTest {
    @Test
    void attacksNeedToolPhaseFourAndPlayersAreToldWhenTheyAreOff() {
        Settings three = Settings.of(b -> b.toolPhase = 3);
        assertFalse(three.attacksEnabled());
        String note = three.attacksOffNote();
        assertTrue(note.contains("tool_phase 3") && note.contains("flees"), note);

        Settings four = Settings.of(b -> b.toolPhase = 4);
        assertTrue(four.attacksEnabled());
        assertEquals("", four.attacksOffNote());
        assertTrue(Settings.of(b -> b.toolPhase = 6).attacksEnabled());
    }
}
