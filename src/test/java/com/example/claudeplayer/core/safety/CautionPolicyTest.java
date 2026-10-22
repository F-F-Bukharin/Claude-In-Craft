package com.example.claudeplayer.core.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.claudeplayer.core.safety.CautionPolicy.Situation;
import com.example.claudeplayer.core.safety.CautionPolicy.Stance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** The truth table from the modmap (SYSTEMS 6): six levels against the situations that matter. */
class CautionPolicyTest {

    @ParameterizedTest
    @EnumSource(CautionLevel.class)
    void playersAreNeverTargets(CautionLevel level) {
        Situation player = new Situation(false, true, 1, true, 0.1, false, true);
        assertEquals("never_attack_players", CautionPolicy.check(level, player).reason());
    }

    @Test
    void passiveMobsAreNotTargets() {
        Situation cow = new Situation(false, false, 1, true, 1, true, false);
        assertEquals("not_hostile", CautionPolicy.check(CautionLevel.GUNS_BLAZING, cow).reason());
    }

    @ParameterizedTest(name = "{0} dist={1} adj={2} hp={3} escape={4} hurtFleeing={5} -> {6}")
    @CsvSource({
            // level,       distance, adjacent, hp,   escape, hurtFleeing, expected
            "GUNS_BLAZING,  10,       false,    1.0,  true,   false,       allow",
            "GUNS_BLAZING,  20,       false,    1.0,  true,   false,       out_of_range",
            "GUNS_BLAZING,  1,        true,     0.10, true,   false,       low_hp_retreat",
            "GUNS_BLAZING,  1,        true,     0.10, false,  false,       allow",
            "BLOODTHIRSTY,  10,       false,    1.0,  true,   false,       allow",
            "BLOODTHIRSTY,  14,       false,    1.0,  true,   false,       out_of_range",
            "BLOODTHIRSTY,  1,        true,     0.25, true,   false,       low_hp_retreat",
            "BLOODTHIRSTY,  1,        true,     0.25, false,  false,       allow",
            "READY,         5,        false,    1.0,  true,   false,       allow",
            "READY,         8,        false,    1.0,  true,   false,       out_of_range",
            "READY,         5,        false,    0.10, true,   false,       allow",
            "REASONABLE,    1,        true,     1.0,  true,   false,       retreat_first",
            "REASONABLE,    1,        true,     1.0,  false,  false,       allow",
            "REASONABLE,    5,        false,    1.0,  false,  false,       not_adjacent",
            "CORNERED,      1,        true,     1.0,  true,   false,       escape_first",
            "CORNERED,      1,        true,     1.0,  true,   true,        allow",
            "CORNERED,      1,        true,     1.0,  false,  false,       allow",
            "CORNERED,      5,        false,    1.0,  false,  true,        not_adjacent",
            "INNOCENT,      1,        true,     0.10, false,  true,        innocent",
            "INNOCENT,      10,       false,    1.0,  true,   false,       innocent",
    })
    void truthTable(CautionLevel level, double distance, boolean adjacent, double hp, boolean escape,
                    boolean hurtFleeing, String expected) {
        CautionPolicy.Verdict v = CautionPolicy.check(level, new Situation(true, false, distance, adjacent, hp, escape, hurtFleeing));
        assertEquals(expected, v.allowed() ? "allow" : v.reason());
    }

    @ParameterizedTest(name = "{0} dist={1} adj={2} hp={3} escape={4} hurtFleeing={5} -> {6}")
    @CsvSource({
            "GUNS_BLAZING, 10, false, 1.0,  true,  false, FIGHT",
            "GUNS_BLAZING, 1,  true,  0.10, true,  false, FLEE",
            "BLOODTHIRSTY, 15, false, 1.0,  true,  false, HOLD",
            "READY,        9,  false, 1.0,  true,  false, HOLD",
            "READY,        3,  false, 1.0,  true,  false, FIGHT",
            "REASONABLE,   1,  true,  1.0,  true,  false, FLEE",
            "REASONABLE,   1,  true,  1.0,  false, false, FIGHT",
            "CORNERED,     1,  true,  1.0,  true,  false, FLEE",
            "CORNERED,     1,  true,  1.0,  true,  true,  FIGHT",
            "INNOCENT,     1,  true,  0.5,  false, true,  FLEE",
            "INNOCENT,     1,  true,  0.2,  false, true,  FLEE_AND_CALL_FOR_HELP",
    })
    void advice(CautionLevel level, double distance, boolean adjacent, double hp, boolean escape,
                boolean hurtFleeing, Stance expected) {
        assertEquals(expected, CautionPolicy.advise(level, new Situation(true, false, distance, adjacent, hp, escape, hurtFleeing)));
    }
}
