package com.example.claudeplayer.body;

import com.mojang.authlib.GameProfile;
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

    @Override
    public void tick() {
        super.tick();
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
