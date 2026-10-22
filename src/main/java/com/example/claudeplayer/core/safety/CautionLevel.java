package com.example.claudeplayer.core.safety;

/** Caution levels 0 to 5. The names and meanings are F's; the numbers in the rules are tuned after gate G4. */
public enum CautionLevel {
    GUNS_BLAZING("Guns blazing", "attack any hostile within 16 blocks and pursue it; retreat only below 15% HP"),
    BLOODTHIRSTY("Bloodthirsty", "attack any hostile within 12 blocks; retreat only below 30% HP"),
    READY("Ready and willing", "attack hostiles within 6 blocks; hold your ground, don't chase"),
    REASONABLE("Reasonable", "avoid fights: retreat if you can; fight only when a threat is adjacent and there is no way out"),
    CORNERED("Cornered animal", "always flee first; fight back only after escape fails (no path, or hurt while fleeing)"),
    INNOCENT("Innocent", "never attack, even when hit; flee, and call for help below 30% HP");

    public final String title;
    public final String rule;

    CautionLevel(String title, String rule) {
        this.title = title;
        this.rule = rule;
    }

    public int number() {
        return ordinal();
    }

    public static CautionLevel of(int number) {
        return values()[Math.max(0, Math.min(values().length - 1, number))];
    }
}
