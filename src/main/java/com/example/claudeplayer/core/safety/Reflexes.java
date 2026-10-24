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

    /** A hostile this close (straight line, blocks) after its first report is still chasing. */
    public static final double CHASE_RANGE = 6;
    /** After a decision that moved the body, the chase rule waits this long before re-running it for that mob. */
    public static final long CHASE_GAP_TICKS = 40;
    /** After a decision with no move (NONE), the next check for that mob comes this soon. */
    public static final long CHASE_RECHECK_TICKS = 10;

    /** Distance on the ground plane; height is ignored. */
    public static double horizontal(double dx, double dz) {
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * The reflex move for a threat. FIGHT starts the fight routine, which walks to the target and swings until the
     * policy stops it; without attacks (tool_phase below 4) a fight becomes a flee.
     */
    public static Move decide(CautionLevel level, CautionPolicy.Situation s, boolean litCreeper, boolean attacksEnabled) {
        if (litCreeper) return Move.FLEE;
        if (s.targetPlayer() || !s.targetHostile()) return Move.NONE;
        return switch (CautionPolicy.advise(level, s)) {
            case FLEE, FLEE_AND_CALL_FOR_HELP -> Move.FLEE;
            case HOLD -> Move.NONE;
            case FIGHT -> attacksEnabled ? Move.FIGHT : Move.FLEE;
        };
    }

    /**
     * {@link #decide} for a threat that may be beyond {@code hostileRange} (a hit from far off: a ghast's fireball,
     * a long arrow). Such a threat gets NONE: flee and fight both work on the hostiles within range, so either move
     * would only scrap work and then answer ok:no_threats or out_of_range. The model still hears the hit.
     */
    public static Move decide(CautionLevel level, CautionPolicy.Situation s, boolean litCreeper, boolean attacksEnabled,
                              double hostileRange) {
        if (s.distance() > hostileRange) return Move.NONE;
        return decide(level, s, litCreeper, attacksEnabled);
    }

    /**
     * The chase rule: a flee ends after about 10 blocks and a threat is reported once, so a mob that keeps up gets
     * the decision re-run while no level 3 action is running, once {@code now} reaches the mob's {@code dueAt}
     * ({@link #chaseWait}). Only a mob the model {@code heard} about (its first reflex was delivered) can chase: a
     * re-fire tells the model nothing.
     */
    public static boolean chaseDue(double distance, long now, long dueAt, boolean heard, boolean levelThreeRunning) {
        return heard && distance <= CHASE_RANGE && !levelThreeRunning && now >= dueAt;
    }

    /**
     * Ticks until a mob's next chase check: 2 s after a move, half a second after NONE, which queued nothing, so a
     * HOLD that turns into a FIGHT (the mob closed to adjacent) is not held back the full gap.
     */
    public static long chaseWait(boolean moved) {
        return moved ? CHASE_GAP_TICKS : CHASE_RECHECK_TICKS;
    }

    /** "lava", "fire" or "drop" when the spot is deadly, null when safe. A fall of n blocks deals n - 3 damage. */
    public static String hazard(String feetBlock, String groundBlock, int dropDepth, boolean landsInWater, double hp) {
        if ("minecraft:lava".equals(feetBlock) || "minecraft:lava".equals(groundBlock)) return "lava";
        if ("minecraft:fire".equals(feetBlock) || "minecraft:soul_fire".equals(feetBlock)) return "fire";
        if (!landsInWater && dropDepth - 3 >= hp) return "drop";
        return null;
    }
}
