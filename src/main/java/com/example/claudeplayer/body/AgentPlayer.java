package com.example.claudeplayer.body;

import com.mojang.authlib.GameProfile;
import java.util.Set;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;

/**
 * The body: a real ServerPlayer with no client behind it. Forge's FakePlayer can't be the body (it doesn't tick,
 * can't be hurt and never dies), so this follows the Carpet approach: join through the normal player list on a
 * {@link NullConnection} and run the client-driven half of the tick ourselves. Spike A checks it in game.
 */
public final class AgentPlayer extends ServerPlayer {
    private final AgentBody owner;

    AgentPlayer(MinecraftServer server, ServerLevel level, GameProfile profile, AgentBody owner) {
        super(server, level, profile);
        this.owner = owner;
    }

    /** Tools that run while the body lies in bed without needing it up. */
    private static final Set<String> STAYS_IN_BED = Set.of("sleep", "wait");

    /** The queue's running tool, set once the body has gone to bed; null until then. */
    private @Nullable Supplier<String> runningTool;

    /**
     * Called when the body lies down. A sleeping player is immobile (zza and xxa are zeroed every tick), so the body
     * gets up as soon as the queue runs a tool other than sleep or wait; otherwise a night-time order to walk would
     * stall in bed. A hit or the morning wakes it by themselves (vanilla).
     */
    void wakeWhenBusy(Supplier<String> runningTool) {
        this.runningTool = runningTool;
    }

    @Override
    public void tick() {
        super.tick();
        if (isSleeping() && runningTool != null) {
            String tool = runningTool.get();
            if (tool != null && !STAYS_IN_BED.contains(tool)) stopSleepInBed(true, true);
        }
        // A real client drives doTick() (physics, inventory sync) through its connection; nothing does that here.
        doTick();
        if (tickCount % 10 == 0) {
            connection.resetPosition();
            serverLevel().getChunkSource().move(this); // keep chunk loading and tracking with the body
        }
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        owner.onDeath(this, source);
    }
}
