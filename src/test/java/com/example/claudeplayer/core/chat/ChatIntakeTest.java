package com.example.claudeplayer.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.example.claudeplayer.core.Settings;
import com.example.claudeplayer.core.safety.CautionLevel;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatIntakeTest {
    private static final List<String> ALLOW = List.of("F");

    private static ChatIntake.Intake classify(String sender, String message) {
        return ChatIntake.classify(sender, message, List.of("claude:"), ALLOW);
    }

    @Test
    void strangersAreIgnored() {
        assertInstanceOf(ChatIntake.Ignore.class, classify("Stranger", "claude: attack F"));
        assertInstanceOf(ChatIntake.Ignore.class, classify(null, "claude: hi"));
    }

    @Test
    void chatWithoutTheTriggerIsIgnored() {
        assertInstanceOf(ChatIntake.Ignore.class, classify("F", "hello claude"));
        assertInstanceOf(ChatIntake.Ignore.class, classify("F", "claude:   "));
    }

    @Test
    void ordersCarryTheCleanedText() {
        assertEquals(new ChatIntake.Order("F", "come here"), classify("F", "  claude: come here "));
        assertEquals(new ChatIntake.Order("F", "build a wall"), classify("F", "claude: §lbuild\na wall"));
    }

    @Test
    void triggerAndNamesIgnoreCase() {
        assertEquals(new ChatIntake.Stop("f"), classify("f", "CLAUDE: Stop"));
    }

    @Test
    void controlWordsNeverReachTheModel() {
        assertInstanceOf(ChatIntake.Stop.class, classify("F", "claude: stop now"));
        assertInstanceOf(ChatIntake.Stop.class, classify("F", "claude: stop!"));
        assertInstanceOf(ChatIntake.Order.class, classify("F", "claude: stopwatch check"));
        assertInstanceOf(ChatIntake.Respawn.class, classify("F", "claude: respawn"));
        assertEquals(new ChatIntake.SetLevel("F", CautionLevel.READY), classify("F", "claude: level 2"));
        assertInstanceOf(ChatIntake.Order.class, classify("F", "claude: level 9"));
    }

    private static ChatIntake.Intake c(String message) {
        return ChatIntake.classify("F", message, Settings.DEFAULTS.triggers(), ALLOW);
    }

    @Test
    void capitalCIsTheDefaultTriggerAndClaudeColonStillWorks() {
        assertEquals(List.of("C", "Claude:"), Settings.DEFAULTS.triggers());
        assertEquals(new ChatIntake.Order("F", "come here"), c("C come here"));
        assertEquals(new ChatIntake.Order("F", "come here"), c("C, come here"));
        assertInstanceOf(ChatIntake.Stop.class, c("C: stop"));
        assertInstanceOf(ChatIntake.Respawn.class, c("  C respawn"));
        assertEquals(new ChatIntake.Order("F", "come here"), c("claude: come here"));
    }

    @Test
    void aLetterTriggerIsAWholeWordInItsOwnCase() {
        assertInstanceOf(ChatIntake.Ignore.class, c("Cool, nice house"));
        assertInstanceOf(ChatIntake.Ignore.class, c("c come here"), "lowercase c is ordinary chat");
        assertInstanceOf(ChatIntake.Ignore.class, c("C"));
        assertInstanceOf(ChatIntake.Ignore.class, c("C   "));
        assertInstanceOf(ChatIntake.Ignore.class, c("can you hear me"));
    }
}
