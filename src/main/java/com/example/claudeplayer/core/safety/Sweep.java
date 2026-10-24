package com.example.claudeplayer.core.safety;

/**
 * Vanilla 1.20.1's sweep condition in {@code Player.attack}, read from the Forge 47.4.28 bytecode. A sweep hurts every
 * living thing in the target's box inflated by 1, 0.25, 1 (players, villagers, babies and pets included), so the
 * body never sweeps: body/Swing marks each swing as a sprint hit, and a charged sprint hit takes the knockback branch
 * instead of the sweep. An uncharged swing never sweeps either. This class is the model the test pins down.
 */
public final class Sweep {
    private Sweep() {}

    /** Above this attack strength the swing is "charged" (vanilla's 0.9f). */
    public static final float FULL_CHARGE = 0.9f;

    /**
     * Whether a swing sweeps. {@code charge}: getAttackStrengthScale(0.5f); {@code walked}: walkDist - walkDistO this
     * tick; {@code speed}: getSpeed(); {@code sweepTool}: the main hand can perform SWORD_SWEEP.
     */
    public static boolean sweeps(float charge, boolean sprinting, boolean critical, boolean onGround, double walked, double speed,
                                 boolean sweepTool) {
        boolean charged = charge > FULL_CHARGE;
        boolean sprintKnockback = sprinting && charged; // vanilla: knockback + 1 and the sweep is skipped
        return charged && !critical && !sprintKnockback && onGround && walked < speed && sweepTool;
    }
}
