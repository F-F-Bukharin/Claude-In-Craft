package com.example.claudeplayer.core.safety;

/**
 * Decides in code what the body does the moment a threat appears, before the model is asked: flee, fight, or
 * leave it to the model. The caution level picks the stance through {@link CautionPolicy#advise}; this only turns
 * the stance into a move. Players are never attacked by a reflex, and a lit creeper is always fled from.
 *
 * <p>Units: hp is in half-hearts as {@code LivingEntity#getHealth} reports it; dropDepth is in empty blocks below
 * the probed feet block.
 */
public final class Reflexes {
    private Reflexes() {}

    public enum Move { FLEE, FIGHT, NONE }

    /** Distance on the ground plane; height is ignored. */
    public static double horizontal(double dx, double dz) {
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** The reflex move: FIGHT only within reach, FLEE only within hostile_range (inFleeRange), else NONE for Claude. */
    public static Move decide(CautionLevel level, CautionPolicy.Situation s, boolean litCreeper, boolean attacksEnabled,
                              double reach, boolean inFleeRange) {
        if (litCreeper) return Move.FLEE;
        if (s.targetPlayer() || !s.targetHostile()) return Move.NONE;
        return switch (CautionPolicy.advise(level, s)) {
            case FLEE, FLEE_AND_CALL_FOR_HELP -> inFleeRange ? Move.FLEE : Move.NONE;
            case HOLD -> Move.NONE;
            case FIGHT -> {
                if (!attacksEnabled) yield inFleeRange ? Move.FLEE : Move.NONE;
                yield s.distance() <= reach ? Move.FIGHT : Move.NONE;
            }
        };
    }

    /** "lava", "fire" or "drop" when the spot is deadly, null when safe. A fall of n blocks deals n - 3 damage. */
    public static String hazard(String feetBlock, String groundBlock, int dropDepth, boolean landsInWater, double hp) {
        if ("minecraft:lava".equals(feetBlock) || "minecraft:lava".equals(groundBlock)) return "lava";
        if ("minecraft:fire".equals(feetBlock) || "minecraft:soul_fire".equals(feetBlock)) return "fire";
        if (!landsInWater && dropDepth - 3 >= hp) return "drop";
        return null;
    }
}
