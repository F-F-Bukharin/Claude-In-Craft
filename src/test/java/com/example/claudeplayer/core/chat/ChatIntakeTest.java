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
    void withTheTriggerOffEveryAllowlistedLineIsAnOrder() {
        List<String> c = List.of("C", "Claude:");
        assertEquals(new ChatIntake.Order("F", "come here"), ChatIntake.classify("F", "come here", c, ALLOW, false));
        assertEquals(new ChatIntake.Order("F", "come here"), ChatIntake.classify("F", "C come here", c, ALLOW, false), "C still works");
        assertEquals(new ChatIntake.Stop("F"), ChatIntake.classify("F", "stop", c, ALLOW, false));
        assertEquals(new ChatIntake.SetLevel("F", CautionLevel.of(1)), ChatIntake.classify("F", "level 1", c, ALLOW, false));
        assertInstanceOf(ChatIntake.Ignore.class, ChatIntake.classify("Stranger", "attack F", c, ALLOW, false));
        assertInstanceOf(ChatIntake.Ignore.class, ChatIntake.classify("F", "   ", c, ALLOW, false));
        assertInstanceOf(ChatIntake.Ignore.class, ChatIntake.classify("F", "come here", c, ALLOW, true), "on by default");
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
    void autonomyPhrasesTurnItOnAndTrailingWordsAreFine() {
        ChatIntake.Autonomy on = new ChatIntake.Autonomy("F", true);
        assertEquals(on, c("C go do your thing, I'll call you when you're needed"));
        assertEquals(on, c("C Do your thing!"));
        assertEquals(on, c("C go auto"));
        assertEquals(on, c("C auto"));
        assertEquals(on, c("C autonomous"));
        assertEquals(on, c("C free-roam for a while"));
        assertEquals(on, c("Claude: survive"));
        assertInstanceOf(ChatIntake.Ignore.class, classify("Stranger", "claude: go do your thing"));
    }

    @Test
    void autoOffEndsAutonomyWithoutTheKillSwitchAndStopStillHalts() {
        ChatIntake.Autonomy off = new ChatIntake.Autonomy("F", false);
        assertEquals(off, c("C auto off"));
        assertEquals(off, c("C stop auto."));
        assertEquals(off, c("C: Auto off please"));
        assertInstanceOf(ChatIntake.Stop.class, c("C stop"));
        assertInstanceOf(ChatIntake.Stop.class, c("C stop autonomy"));
    }

    @Test
    void ordinaryOrdersStayOrders() {
        assertEquals(new ChatIntake.Order("F", "go to the house"), c("C go to the house"));
        assertEquals(new ChatIntake.Order("F", "survey the area"), c("C survey the area"));
        assertEquals(new ChatIntake.Order("F", "automate the farm"), c("C automate the farm"));
        assertEquals(new ChatIntake.Order("F", "do your homework"), c("C do your homework"));
        assertEquals(new ChatIntake.Order("F", "tell me about survival"), c("C tell me about survival"));
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
