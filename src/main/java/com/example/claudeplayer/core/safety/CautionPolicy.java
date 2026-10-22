package com.example.claudeplayer.core.safety;

/**
 * Decides in code whether an attack is allowed at a caution level. The prompt only describes the levels; the
 * executor calls {@link #check} before every swing and sends a denial back to the model with its reason, so the
 * model learns the rule instead of retrying blindly.
 *
 * <p>Terms: hostile = monster-category mob; adjacent = within 2 blocks; escape exists = a path of at most
 * escape_max_steps to a tile at least escape_distance from every threat; hurt while fleeing = damaged in the last
 * 3 s while moving. Players are never valid targets, at any level.
 */
public final class CautionPolicy {
    private CautionPolicy() {}

    public record Situation(boolean targetHostile, boolean targetPlayer, double distance, boolean adjacent,
                            double hpFraction, boolean escapeExists, boolean hurtWhileFleeing) {}

    public record Verdict(boolean allowed, String reason) {
        static final Verdict ALLOW = new Verdict(true, null);

        static Verdict deny(String reason) {
            return new Verdict(false, reason);
        }
    }

    /** What the level wants done about a threat; sent to the model as a hint with each interrupt. */
    public enum Stance { FIGHT, HOLD, FLEE, FLEE_AND_CALL_FOR_HELP }

    public static Verdict check(CautionLevel level, Situation s) {
        if (s.targetPlayer()) return Verdict.deny("never_attack_players");
        if (!s.targetHostile()) return Verdict.deny("not_hostile");
        return switch (level) {
            case GUNS_BLAZING -> inRange(s, 16, 0.15);
            case BLOODTHIRSTY -> inRange(s, 12, 0.30);
            case READY -> s.distance() > 6 ? Verdict.deny("out_of_range") : Verdict.ALLOW;
            case REASONABLE -> !s.adjacent() ? Verdict.deny("not_adjacent")
                    : s.escapeExists() ? Verdict.deny("retreat_first") : Verdict.ALLOW;
            case CORNERED -> !s.adjacent() ? Verdict.deny("not_adjacent")
                    : s.escapeExists() && !s.hurtWhileFleeing() ? Verdict.deny("escape_first") : Verdict.ALLOW;
            case INNOCENT -> Verdict.deny("innocent");
        };
    }

    public static Stance advise(CautionLevel level, Situation s) {
        return switch (level) {
            case GUNS_BLAZING -> retreating(s, 0.15) ? Stance.FLEE : Stance.FIGHT;
            case BLOODTHIRSTY -> retreating(s, 0.30) ? Stance.FLEE : s.distance() <= 12 ? Stance.FIGHT : Stance.HOLD;
            case READY -> s.distance() <= 6 ? Stance.FIGHT : Stance.HOLD;
            case REASONABLE -> s.escapeExists() ? Stance.FLEE : s.adjacent() ? Stance.FIGHT : Stance.HOLD;
            case CORNERED -> s.escapeExists() && !s.hurtWhileFleeing() ? Stance.FLEE
                    : s.adjacent() ? Stance.FIGHT : Stance.HOLD;
            case INNOCENT -> s.hpFraction() < 0.30 ? Stance.FLEE_AND_CALL_FOR_HELP : Stance.FLEE;
        };
    }

    // Within range, and not in the level's low-HP retreat (which only applies when there is somewhere to go).
    private static Verdict inRange(Situation s, double range, double retreatBelow) {
        if (s.distance() > range) return Verdict.deny("out_of_range");
        return retreating(s, retreatBelow) ? Verdict.deny("low_hp_retreat") : Verdict.ALLOW;
    }

    private static boolean retreating(Situation s, double below) {
        return s.hpFraction() < below && s.escapeExists();
    }
}
