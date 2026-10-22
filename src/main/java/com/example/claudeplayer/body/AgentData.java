package com.example.claudeplayer.body;

import com.example.claudeplayer.ClaudePlayerMod;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/** Per-world state saved with the overworld: home, caution level, and whether the body should be back after a restart. */
public final class AgentData extends SavedData {
    private ResourceKey<Level> homeDimension = Level.OVERWORLD;
    private BlockPos home;
    private int level = -1; // -1: use default_level
    private boolean spawned;
    private boolean dead;

    public static AgentData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(AgentData::load, AgentData::new, ClaudePlayerMod.MODID);
    }

    private static AgentData load(CompoundTag tag) {
        AgentData d = new AgentData();
        if (tag.contains("home")) d.home = BlockPos.of(tag.getLong("home"));
        ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("home_dim"));
        if (dimension != null) d.homeDimension = ResourceKey.create(Registries.DIMENSION, dimension);
        d.level = tag.contains("level") ? tag.getInt("level") : -1;
        d.spawned = tag.getBoolean("spawned");
        d.dead = tag.getBoolean("dead");
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        if (home != null) tag.putLong("home", home.asLong());
        tag.putString("home_dim", homeDimension.location().toString());
        tag.putInt("level", level);
        tag.putBoolean("spawned", spawned);
        tag.putBoolean("dead", dead);
        return tag;
    }

    public @Nullable BlockPos home() {
        return home;
    }

    public ResourceKey<Level> homeDimension() {
        return homeDimension;
    }

    public void setHome(ResourceKey<Level> dimension, BlockPos pos) {
        homeDimension = dimension;
        home = pos.immutable();
        setDirty();
    }

    public int level() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
        setDirty();
    }

    public boolean spawned() {
        return spawned;
    }

    public void setSpawned(boolean spawned) {
        this.spawned = spawned;
        setDirty();
    }

    public boolean dead() {
        return dead;
    }

    public void setDead(boolean dead) {
        this.dead = dead;
        setDirty();
    }
}
