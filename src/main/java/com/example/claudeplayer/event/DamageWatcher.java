package com.example.claudeplayer.event;

import com.example.claudeplayer.AgentRuntime;
import com.example.claudeplayer.body.AgentPlayer;
import com.example.claudeplayer.body.SnapshotBuilder;
import com.example.claudeplayer.core.safety.CautionLevel;
import com.example.claudeplayer.core.safety.CautionPolicy;
import com.example.claudeplayer.core.safety.ThreatTracker;
import com.example.claudeplayer.core.safety.WorldText;
import java.util.Locale;
import javax.annotation.Nullable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingHurtEvent;

/**
 * Turns hits and approaching hostiles into loop interrupts, debounced by {@link ThreatTracker} (one per threat,
 * at most one every 5 s). An interrupt is held here and delivered on the next runtime tick, never from inside
 * another entity's tick. At caution level 5 it also calls for help below 30% HP.
 */
public final class DamageWatcher {
    private static final int SCAN_TICKS = 10;
    private static final int HURT_WINDOW_TICKS = 60;
    private static final int HELP_COOLDOWN_TICKS = 300;

    private final AgentRuntime rt;
    private final ThreatTracker threats = new ThreatTracker(100, 600);
    private long lastHurt = -1_000;
    private long lastHelp = -1_000;
    private boolean hurtWhileMoving;
    private String pending;

    public DamageWatcher(AgentRuntime rt) {
        this.rt = rt;
    }

    public void onHurt(LivingHurtEvent event) {
        AgentPlayer p = rt.body().player();
        if (p == null || event.getEntity() != p) return;
        long now = rt.ticks();
        lastHurt = now;
        String running = rt.queue().runningTool();
        hurtWhileMoving = "move_to".equals(running) || "follow_player".equals(running);
        Entity attacker = event.getSource().getEntity();
        float left = Math.max(0, p.getHealth() - event.getAmount());
        String who = attacker != null ? describe(attacker) : event.getSource().getMsgId();
        if (rt.level() == CautionLevel.INNOCENT && left / p.getMaxHealth() < 0.3f && now - lastHelp >= HELP_COOLDOWN_TICKS) {
            lastHelp = now;
            rt.say("Help! I'm being attacked at " + p.blockPosition().toShortString() + "!");
        }
        if (threats.report("hit:" + (attacker != null ? attacker.getId() : who), now)) {
            pending = String.format(Locale.ROOT, "hit by %s for %.1f HP (%.1f/%.0f left). %s",
                    who, event.getAmount(), left, p.getMaxHealth(), advice(p, attacker));
        }
    }

    public void tick() {
        AgentPlayer p = rt.body().player();
        if (p == null) {
            pending = null;
            return;
        }
        long now = rt.ticks();
        if (pending == null && now % SCAN_TICKS == 0) {
            for (Mob mob : SnapshotBuilder.hostiles(p, rt.settings().hostileRange())) {
                if (threats.report("near:" + mob.getId(), now)) {
                    pending = String.format(Locale.ROOT, "%s is %.1f blocks away. %s", describe(mob), p.distanceTo(mob), advice(p, mob));
                    break;
                }
            }
        }
        if (pending != null) {
            String reason = pending;
            pending = null;
            rt.loop().interrupt(reason);
        }
    }

    /** Hurt in the last 3 s while moving: the policy's proxy for "escape failed". */
    public boolean hurtWhileFleeing() {
        return hurtWhileMoving && rt.ticks() - lastHurt <= HURT_WINDOW_TICKS;
    }

    private String advice(AgentPlayer p, @Nullable Entity threat) {
        CautionLevel level = rt.level();
        double distance = threat == null ? 0 : p.distanceTo(threat);
        CautionPolicy.Situation s = new CautionPolicy.Situation(threat != null && SnapshotBuilder.isHostile(threat),
                threat instanceof Player, distance, distance <= 2.0, p.getHealth() / p.getMaxHealth(),
                rt.escape().escapeExists(p, SnapshotBuilder.hostiles(p, rt.settings().hostileRange())), hurtWhileFleeing());
        String stance = CautionPolicy.advise(level, s).name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return "Caution level " + level.number() + " (" + level.title + ") says: " + stance + ".";
    }

    private static String describe(Entity e) {
        String name = e instanceof Player player ? WorldText.clean(player.getGameProfile().getName(), WorldText.NAME_MAX)
                : SnapshotBuilder.customName(e);
        String type = SnapshotBuilder.typeId(e);
        return (name != null ? type + " \"" + name + "\"" : type) + " #" + e.getId();
    }
}
