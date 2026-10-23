package com.example.claudeplayer.core.safety;

import java.util.Locale;

/**
 * The fight routine's rules. Every tick the routine asks {@link CautionPolicy#check} about its target; the verdict
 * decides whether it strikes, walks closer, or ends. Walking out of the level's range ends the fight as ok (the
 * target got away), any other denial ends it with the denial's reason, and being beyond reach is not a denial: the
 * routine walks closer.
 */
public final class FightRules {
    private FightRules() {}

    /** A swing waits for the vanilla cooldown to charge this far ({@code getAttackStrengthScale(0.5f)}). */
    public static final float SWING_CHARGE = 0.9f;

    public enum Step { STRIKE, APPROACH, LEFT_RANGE, DENIED }

    public static Step step(CautionPolicy.Verdict verdict, double distance, double reach) {
        if (!verdict.allowed()) {
            return "out_of_range".equals(verdict.reason()) || "not_adjacent".equals(verdict.reason()) ? Step.LEFT_RANGE : Step.DENIED;
        }
        return distance <= reach ? Step.STRIKE : Step.APPROACH;
    }

    public static boolean swingReady(double distance, double reach, float charge) {
        return distance <= reach && charge >= SWING_CHARGE;
    }

    /** "won: zombie #213, took 4 HP", plus what was put on first ({@code equipped}, empty for nothing). */
    public static String won(String who, double taken, String equipped) {
        double hp = Math.max(0, taken);
        String took = hp == Math.rint(hp) ? String.valueOf((long) hp) : String.format(Locale.ROOT, "%.1f", hp);
        return "won: " + who + ", took " + took + " HP" + (equipped.isEmpty() ? "" : "; equipped " + equipped);
    }
}
