package com.example.claudeplayer.body;

import com.example.claudeplayer.ClaudePlayerMod;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Per-world state saved with the overworld: home, caution level, whether the body should be back after a restart,
 * and the blocks Claude placed (which the break rule lets it mine again).
 */
public final class AgentData extends SavedData {
    private static final int MAX_PLACED = 4096;

    private ResourceKey<Level> homeDimension = Level.OVERWORLD;
    private BlockPos home;
    private int level = -1; // -1: use default_level
    private boolean spawned;
    private boolean dead;
    private final Map<GlobalPos, String> placed = new LinkedHashMap<>(); // position -> block id, oldest first

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
        for (Tag t : tag.getList("placed", Tag.TAG_COMPOUND)) {
            CompoundTag e = (CompoundTag) t;
            ResourceLocation dim = ResourceLocation.tryParse(e.getString("dim"));
            if (dim != null) d.placed.put(GlobalPos.of(ResourceKey.create(Registries.DIMENSION, dim), BlockPos.of(e.getLong("pos"))), e.getString("block"));
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        if (home != null) tag.putLong("home", home.asLong());
        tag.putString("home_dim", homeDimension.location().toString());
        tag.putInt("level", level);
        tag.putBoolean("spawned", spawned);
        tag.putBoolean("dead", dead);
        ListTag list = new ListTag();
        placed.forEach((at, block) -> {
            CompoundTag e = new CompoundTag();
            e.putString("dim", at.dimension().location().toString());
            e.putLong("pos", at.pos().asLong());
            e.putString("block", block);
            list.add(e);
        });
        tag.put("placed", list);
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

    /** The block id Claude placed at this position, or null if it placed nothing there. */
    public @Nullable String placedBlock(ResourceKey<Level> dimension, BlockPos pos) {
        return placed.get(GlobalPos.of(dimension, pos.immutable()));
    }

    /** Records a block Claude placed; past the cap the oldest record goes. */
    public void addPlaced(ResourceKey<Level> dimension, BlockPos pos, String blockId) {
        GlobalPos at = GlobalPos.of(dimension, pos.immutable());
        placed.remove(at);
        placed.put(at, blockId);
        Iterator<GlobalPos> oldest = placed.keySet().iterator();
        while (placed.size() > MAX_PLACED) {
            oldest.next();
            oldest.remove();
        }
        setDirty();
    }

    public void removePlaced(ResourceKey<Level> dimension, BlockPos pos) {
        if (placed.remove(GlobalPos.of(dimension, pos.immutable())) != null) setDirty();
    }
}
