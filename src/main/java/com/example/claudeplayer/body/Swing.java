package com.example.claudeplayer.body;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * The body's one melee swing, shared by attack_entity, fight and hunt. Vanilla's charged sword swing on the ground
 * sweeps every living thing next to the target (players, villagers, babies and pets included), so each swing is
 * marked as a sprint hit: Player.attack then takes its sprint-knockback branch, which skips the sweep (core/safety/Sweep
 * models the condition). The sprint flag is restored right after. A sprint hit also knocks the target back one step
 * further and costs no critical hit, which the body never lands anyway (crits need a fall).
 */
final class Swing {
    private Swing() {}

    /** Hits {@code target} with no sweep and swings the main hand. Server thread only. */
    static void hit(Player body, Entity target) {
        boolean sprinting = body.isSprinting();
        body.setSprinting(true);
        try {
            body.attack(target);
        } finally {
            body.setSprinting(sprinting);
        }
        body.swing(InteractionHand.MAIN_HAND, true);
    }
}
