package com.example.claudeplayer.core.safety;

import java.util.Comparator;
import java.util.List;

/**
 * Food choices in code: which food the eat tool picks, and when the auto-eat reflex eats on its own (no model
 * call). Food is the 0 to 20 hunger bar as {@code FoodData#getFoodLevel} reports it; 20 is full.
 */
public final class Hunger {
    private Hunger() {}

    public static final int FULL = 20;
    /** At or below this the reflex eats even while a level 1 or 2 action runs. */
    public static final int STARVING = 6;
    private static final int HUNGRY = 14;
    private static final int HUNGRY_WHEN_HURT = 18; // natural regeneration needs food 18 or more

    /** PLAIN first; SPECIAL has good effects (golden apples), kept for when nothing plain is left; HARMFUL last. */
    public enum Kind { PLAIN, SPECIAL, HARMFUL }

    /** One inventory slot holding food. */
    public record Food(int slot, int nutrition, float saturation, Kind kind) {}

    /** The slot of the best food: plain before special before harmful, then most nutrition, then most saturation; -1 for none. */
    public static int best(List<Food> foods) {
        return foods.stream()
                .min(Comparator.comparing(Food::kind)
                        .thenComparing(Comparator.comparingInt(Food::nutrition).reversed())
                        .thenComparing(Comparator.comparingDouble(Food::saturation).reversed())
                        .thenComparingInt(Food::slot))
                .map(Food::slot).orElse(-1);
    }

    /**
     * Whether the reflex eats now: hungry (food 14 or less, or 18 or less while hurt), plain food at hand, no
     * hostile near, and nothing running. Starving (6 or less) also eats while a level 1 or 2 action runs, never
     * during a level 3 one (flee, attack). {@code runningPrio} is the running action's level, 0 when idle.
     */
    public static boolean shouldAutoEat(int food, boolean hurt, boolean hasPlainFood, boolean hostileNear, int runningPrio) {
        if (!hasPlainFood || hostileNear || runningPrio >= 3) return false;
        if (food <= STARVING) return true;
        boolean hungry = food <= HUNGRY || (hurt && food <= HUNGRY_WHEN_HURT);
        return hungry && runningPrio == 0;
    }
}
