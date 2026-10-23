package com.example.claudeplayer.core.act;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class VisionGateTest {
    private long now = 1_000_000;
    private final VisionGate gate = new VisionGate(15, () -> now);

    @Test
    void aSecondGlanceInTheSameTurnIsDenied() {
        gate.newTurn();
        assertNull(gate.admit());
        assertEquals("one_per_turn", gate.admit());
    }

    @Test
    void theCooldownHoldsAcrossTurnsUntilItRunsOut() {
        gate.newTurn();
        assertNull(gate.admit());
        now += 14_999;
        gate.newTurn();
        assertEquals("cooldown", gate.admit());
        now += 1;
        assertNull(gate.admit(), "15 s after the last glance");
    }

    @Test
    void aNewTurnResetsOnlyThePerTurnRule() {
        VisionGate free = new VisionGate(0, () -> now);
        free.newTurn();
        assertNull(free.admit());
        assertEquals("one_per_turn", free.admit());
        free.newTurn();
        assertNull(free.admit());
    }
}
