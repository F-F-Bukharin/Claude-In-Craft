package com.example.claudeplayer.body;

import com.example.claudeplayer.AgentRuntime;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import io.netty.channel.embedded.EmbeddedChannel;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * The body's lifecycle: spawn, despawn, death, respawn, and restore after a restart. Death is accepted (D3): the
 * corpse is removed once the animation plays, and only a respawn command brings the body back, at home.
 */
public final class AgentBody {
    private static final Logger LOG = LogUtils.getLogger();
    private static final int CORPSE_TICKS = 15; // before vanilla's own 20-tick death removal

    private final AgentRuntime rt;
    private AgentPlayer player;
    private NullConnection connection;
    private int deathTicks = -1;

    public AgentBody(AgentRuntime rt) {
        this.rt = rt;
    }

    /** The body if it is in the world and alive, else null. */
    public @Nullable AgentPlayer player() {
        return player != null && player.isAlive() ? player : null;
    }

    public boolean alive() {
        return player() != null;
    }

    public boolean dead() {
        return rt.data().dead();
    }

    public boolean is(Entity entity) {
        return player != null && entity == player;
    }

    /** /cp spawn: at the caller's position, which becomes home. */
    public String spawnAt(ServerLevel level, Vec3 pos, float yaw) {
        if (player != null) return rt.settings().bodyName() + " is already in the world. Use /cp despawn first.";
        String problem = nameProblem();
        if (problem != null) return problem;
        AgentPlayer p = create(level);
        if (p.isDeadOrDying()) revive(p);
        p.teleportTo(level, pos.x, pos.y, pos.z, yaw, 0f);
        BlockPos home = BlockPos.containing(pos);
        rt.data().setHome(level.dimension(), home);
        rt.data().setSpawned(true);
        rt.data().setDead(false);
        return "Spawned " + p.getGameProfile().getName() + ". Home is " + home.toShortString() + ".";
    }

    /** C respawn and /cp respawn: back at home with full health. */
    public String respawn() {
        if (alive()) return rt.settings().bodyName() + " is alive.";
        BlockPos home = rt.data().home();
        ServerLevel level = rt.server().getLevel(rt.data().homeDimension());
        if (home == null || level == null) return "No home yet. Use /cp spawn first.";
        removeBody(false);
        String problem = nameProblem();
        if (problem != null) return problem;
        AgentPlayer p = create(level);
        revive(p);
        p.teleportTo(level, home.getX() + 0.5, home.getY(), home.getZ() + 0.5, p.getYRot(), 0f);
        rt.data().setSpawned(true);
        rt.data().setDead(false);
        return "Respawned at home.";
    }

    /** /cp despawn. */
    public String despawn() {
        if (player == null) return rt.settings().bodyName() + " isn't in the world.";
        rt.loop().bodyGone();
        removeBody(true);
        rt.data().setSpawned(false);
        return "Despawned.";
    }

    /** Server start: bring the body back where its player data left it, unless it was dead or despawned. */
    public void restore() {
        if (!rt.data().spawned() || rt.data().dead()) return;
        ServerLevel level = rt.server().getLevel(rt.data().homeDimension());
        if (level == null || nameProblem() != null) return;
        create(level); // placeNewPlayer moves it to its saved position and dimension
        LOG.info("Restored {}", rt.settings().bodyName());
    }

    /** Server stop: remove quietly; the saved flags bring it back next start. */
    public void despawnForShutdown() {
        removeBody(false);
    }

    public void tick() {
        if (deathTicks >= 0 && ++deathTicks >= CORPSE_TICKS) removeBody(false);
    }

    void onDeath(AgentPlayer p, DamageSource source) {
        if (p != player) return;
        deathTicks = 0;
        rt.data().setDead(true);
        rt.onBodyDied(source.getLocalizedDeathMessage(p).getString());
    }

    private AgentPlayer create(ServerLevel level) {
        String name = rt.settings().bodyName();
        GameProfile profile = new GameProfile(UUIDUtil.createOfflinePlayerUUID(name), name);
        AgentPlayer p = new AgentPlayer(rt.server(), level, profile, this);
        NullConnection conn = new NullConnection();
        new EmbeddedChannel(conn); // fires channelActive, which gives the connection the live channel Forge expects
        rt.server().getPlayerList().placeNewPlayer(conn, p);
        p.setGameMode(GameType.SURVIVAL);
        player = p;
        connection = conn;
        deathTicks = -1;
        return p;
    }

    private void removeBody(boolean announce) {
        AgentPlayer p = player;
        player = null;
        deathTicks = -1;
        if (p == null) return;
        rt.navigator().stop(null);
        if (announce) {
            rt.server().getPlayerList().broadcastSystemMessage(
                    Component.translatable("multiplayer.player.left", p.getDisplayName()).withStyle(ChatFormatting.YELLOW), false);
        }
        p.disconnect();
        rt.server().getPlayerList().remove(p); // saves its player data (inventory, position)
        if (connection != null) connection.channel().close();
        connection = null;
    }

    private static void revive(AgentPlayer p) {
        p.setHealth(p.getMaxHealth());
        p.deathTime = 0;
        p.removeAllEffects();
        p.clearFire();
        p.getFoodData().setFoodLevel(20);
        p.getFoodData().setSaturation(5f);
    }

    // A real player with the body's name would collide with it in the player list.
    private @Nullable String nameProblem() {
        String name = rt.settings().bodyName();
        ServerPlayer online = rt.server().getPlayerList().getPlayerByName(name);
        if (online != null && !(online instanceof AgentPlayer)) return "A player named " + name + " is online. Change body_name.";
        return null;
    }
}
