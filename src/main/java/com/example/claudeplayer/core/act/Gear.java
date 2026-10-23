package com.example.claudeplayer.core.act;

import java.util.List;

/**
 * Picks the gear to wear before a fight from plain numbers; the game side reads the stats and moves the stacks.
 * Weapons go by main-hand attack damage, then attack speed; armor by armor value, then toughness. The first
 * candidate wins a tie, so the caller lists what is already held or worn first and an equal item never swaps in.
 */
public final class Gear {
    private Gear() {}

    /** The slot number for the piece already worn in an armor slot. */
    public static final int WORN = -1;

    /** An item in {@code slot}: its main stat, and the stat that breaks a tie. */
    public record Candidate(int slot, double main, double tiebreak) {}

    /** The slot of the best weapon (damage, then speed), or -1 with no candidates. */
    public static int bestWeapon(List<Candidate> candidates) {
        return best(candidates, -1);
    }

    /** The slot of the best piece for one armor slot (armor, then toughness): {@link #WORN} keeps what is on. */
    public static int bestArmor(List<Candidate> candidates) {
        return best(candidates, WORN);
    }

    /** A shield goes on only when the off-hand is empty; whatever is held there stays. */
    public static boolean takesShield(boolean offhandEmpty, int shieldSlot) {
        return offhandEmpty && shieldSlot >= 0;
    }

    private static int best(List<Candidate> candidates, int none) {
        Candidate best = null;
        for (Candidate c : candidates) {
            if (best == null || c.main() > best.main() || c.main() == best.main() && c.tiebreak() > best.tiebreak()) best = c;
        }
        return best == null ? none : best.slot();
    }
}
