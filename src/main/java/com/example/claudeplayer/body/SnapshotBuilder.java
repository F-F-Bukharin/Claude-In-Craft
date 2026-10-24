package com.example.claudeplayer.body;

import com.example.claudeplayer.AgentRuntime;
import com.example.claudeplayer.core.StateSnapshot;
import com.example.claudeplayer.core.WorldMap;
import com.example.claudeplayer.core.safety.Reflexes;
import com.example.claudeplayer.core.safety.WorldText;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

/** Builds the immutable {@link StateSnapshot} from the live world, plus the lookups other game code shares. */
public final class SnapshotBuilder {
    private static final int BLOCK_RADIUS = 3;
    private static final int MAX_MOBS = 10;
    private static final int MAX_PLAYERS = 10;
    private static final int MAX_CREATURES = 8;
    /** Passive and neutral mobs and villagers this close (blocks, straight line) go in world_state.creatures. */
    public static final double CREATURE_RANGE = 24;
    private static final int MAX_SLOTS = 12;
    private static final int MAX_BLOCK_TYPES = 12;
    private static final int MAX_POINTS = 12;
    /** world_state.map is 17 by 17; the look tool asks for more. */
    public static final int MAP_RADIUS = 8;
    private static final int MARKER_HEIGHT = 6; // players and mobs this far above or below still show

    private SnapshotBuilder() {}

    /**
     * The top-down map around the body, with players (P) and hostile mobs (!) drawn on it. Up to radius 24 one
     * cell is one block; past that the view stays at most 49 cells across (see {@link WorldMap#scale}). Reads
     * never load chunks: unloaded columns are unknown.
     */
    public static WorldMap.View map(ServerPlayer body, int radius, double hostileRange) {
        ServerLevel world = body.serverLevel();
        BlockPos feet = body.blockPosition();
        List<WorldMap.Marker> markers = new ArrayList<>();
        for (ServerPlayer o : world.players()) {
            if (o != body && Math.abs(o.getY() - body.getY()) <= MARKER_HEIGHT) {
                markers.add(new WorldMap.Marker(o.getBlockX(), o.getBlockZ(), 'P'));
            }
        }
        for (Mob m : hostilesXZ(body, Math.max(radius * 1.5, hostileRange))) {
            if (Math.abs(m.getY() - body.getY()) <= MARKER_HEIGHT) markers.add(new WorldMap.Marker(m.getBlockX(), m.getBlockZ(), '!'));
        }
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos columnProbe = new BlockPos.MutableBlockPos();
        // Level.getHeight does not load chunks (a missing chunk reads as the build floor); the isLoaded check is belt and braces.
        WorldMap.Heights heights = (x, z) -> world.isLoaded(columnProbe.set(x, feet.getY(), z))
                ? world.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) : Integer.MAX_VALUE;
        return WorldMap.render((x, y, z) -> kind(world, probe.set(x, y, z)), heights, feet.getX(), feet.getY(), feet.getZ(), radius, markers);
    }

    private static WorldMap.Kind kind(ServerLevel world, BlockPos pos) {
        if (!world.isLoaded(pos)) return WorldMap.Kind.UNKNOWN; // never load chunks just to draw the map
        BlockState state = world.getBlockState(pos);
        FluidState fluid = state.getFluidState();
        if (fluid.is(FluidTags.LAVA)) return WorldMap.Kind.LAVA;
        if (fluid.is(FluidTags.WATER)) return WorldMap.Kind.WATER;
        if (state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)) return WorldMap.Kind.LAVA; // drawn as L: just as deadly to walk into
        if (state.isAir()) return WorldMap.Kind.OPEN;
        if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)) return WorldMap.Kind.TREE;
        if (state.hasBlockEntity() && world.getBlockEntity(pos) instanceof Container) return WorldMap.Kind.CONTAINER;
        if (state.is(Blocks.FARMLAND)) return WorldMap.Kind.FARMLAND;
        WorldMap.Kind crop = crop(state);
        if (crop != null) return crop;
        return state.getCollisionShape(world, pos).isEmpty() ? WorldMap.Kind.OPEN : WorldMap.Kind.SOLID;
    }

    /** Wheat, carrots, potatoes, beetroots and nether wart: ripe at the block's maximum age. Null for anything else. */
    @Nullable
    private static WorldMap.Kind crop(BlockState state) {
        if (state.getBlock() instanceof CropBlock crop) { // wheat, carrots, potatoes, beetroots
            return WorldMap.crop(crop.getAge(state), crop.getMaxAge());
        }
        if (state.is(Blocks.NETHER_WART)) {
            return WorldMap.crop(state.getValue(NetherWartBlock.AGE), NetherWartBlock.MAX_AGE);
        }
        return null;
    }

    public static StateSnapshot build(AgentRuntime rt) {
        StateSnapshot.Vec home = home(rt);
        int level = rt.level().number();
        AgentPlayer p = rt.body().player();
        if (p == null) return StateSnapshot.absent(rt.body().dead(), level, home);
        ServerLevel world = p.serverLevel();

        List<String> armor = new ArrayList<>();
        for (ItemStack s : p.getInventory().armor) if (!s.isEmpty()) armor.add(itemId(s));
        List<StateSnapshot.Stack> inventory = new ArrayList<>();
        for (ItemStack s : p.getInventory().items) {
            if (s.isEmpty()) continue;
            inventory.add(new StateSnapshot.Stack(itemId(s), s.getCount()));
            if (inventory.size() >= MAX_SLOTS) break;
        }
        List<StateSnapshot.Mob> hostiles = hostiles(p, rt.settings().hostileRange()).stream().limit(MAX_MOBS)
                .map(m -> new StateSnapshot.Mob(m.getId(), typeId(m), customName(m), p.distanceTo(m), m.getHealth(), vec(m.position())))
                .toList();
        List<StateSnapshot.Seen> players = world.players().stream().filter(o -> o != p)
                .sorted(Comparator.comparingDouble(p::distanceToSqr)).limit(MAX_PLAYERS)
                .map(o -> new StateSnapshot.Seen(WorldText.clean(o.getGameProfile().getName(), WorldText.NAME_MAX), p.distanceTo(o), vec(o.position())))
                .toList();
        List<StateSnapshot.Creature> creatures = creatures(p, CREATURE_RANGE, MAX_CREATURES);
        String weather = world.isThundering() ? "thunder" : world.isRaining() ? "rain" : "clear";
        return new StateSnapshot(true, false, vec(p.position()), world.dimension().location().toString(),
                p.getDirection().getSerializedName(), p.getHealth(), p.getMaxHealth(), p.getFoodData().getFoodLevel(),
                p.getMainHandItem().isEmpty() ? "empty" : itemId(p.getMainHandItem()), armor, inventory, hostiles, players,
                creatures, blocksAround(world, p.blockPosition()), pointsAround(world, p.blockPosition()),
                map(p, MAP_RADIUS, rt.settings().hostileRange()), world.getDayTime() % 24000, weather, level, home,
                rt.queue().runningTool(), rt.queue().waitingCount());
    }

    /** Monster-category mobs within range of the body, nearest first. */
    public static List<Mob> hostiles(ServerPlayer body, double range) {
        List<Mob> mobs = new ArrayList<>(body.serverLevel().getEntitiesOfClass(Mob.class, body.getBoundingBox().inflate(range),
                m -> m.isAlive() && isHostile(m) && m.distanceTo(body) <= range));
        mobs.sort(Comparator.comparingDouble(body::distanceToSqr));
        return mobs;
    }

    /**
     * Passive and neutral mobs and villagers within range, nearest first, at most {@code max}: animals, water
     * animals (squid, dolphins), villagers, wandering traders and golems. Monsters are in {@link #hostiles};
     * bats and fish are left out. Each entry says whether it is a baby, whether the body tamed it, and a
     * villager's profession.
     */
    public static List<StateSnapshot.Creature> creatures(ServerPlayer body, double range, int max) {
        List<Mob> mobs = new ArrayList<>(body.serverLevel().getEntitiesOfClass(Mob.class, body.getBoundingBox().inflate(range),
                m -> m.isAlive() && isCreature(m) && m.distanceTo(body) <= range));
        mobs.sort(Comparator.comparingDouble(body::distanceToSqr));
        List<StateSnapshot.Creature> out = new ArrayList<>(Math.min(max, mobs.size()));
        for (Mob m : mobs) {
            if (out.size() >= max) break;
            boolean tamed = m instanceof OwnableEntity o && body.getUUID().equals(o.getOwnerUUID());
            String job = m instanceof Villager v
                    ? shortId(BuiltInRegistries.VILLAGER_PROFESSION.getKey(v.getVillagerData().getProfession()))
                    : null;
            out.add(new StateSnapshot.Creature(m.getId(), typeId(m), customName(m), body.distanceTo(m), m.isBaby(), tamed, job, vec(m.position())));
        }
        return out;
    }

    /** A mob that is neither a monster nor ambient (bats, fish): animals, villagers, traders, golems, squid. */
    public static boolean isCreature(Entity entity) {
        if (!(entity instanceof Mob)) return false;
        MobCategory c = entity.getType().getCategory();
        return c != MobCategory.MONSTER && c != MobCategory.AMBIENT && c != MobCategory.WATER_AMBIENT;
    }

    /** Monster-category mobs within range of the body on the ground plane, at any height, nearest first. */
    public static List<Mob> hostilesXZ(ServerPlayer body, double range) {
        List<Mob> mobs = new ArrayList<>(body.serverLevel().getEntitiesOfClass(Mob.class, body.getBoundingBox().inflate(range, 384, range),
                m -> m.isAlive() && isHostile(m) && horizontal(body, m) <= range));
        mobs.sort(Comparator.comparingDouble(m -> horizontal(body, m)));
        return mobs;
    }

    /** A creeper whose fuse is burning: swelling toward an explosion, or ignited by flint and steel. */
    public static boolean litCreeper(Entity e) {
        return e instanceof Creeper c && (c.getSwellDir() > 0 || c.isIgnited());
    }

    public static boolean isHostile(Entity entity) {
        return entity.getType().getCategory() == MobCategory.MONSTER;
    }

    public static @Nullable StateSnapshot.Vec home(AgentRuntime rt) {
        BlockPos home = rt.data().home();
        return home == null ? null : new StateSnapshot.Vec(home.getX(), home.getY(), home.getZ());
    }

    public static String itemId(ItemStack stack) {
        return shortId(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    public static String typeId(Entity entity) {
        return shortId(EntityType.getKey(entity.getType()));
    }

    public static String blockId(BlockState state) {
        return shortId(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
    }

    public static @Nullable String customName(Entity entity) {
        return entity.hasCustomName() ? WorldText.clean(entity.getCustomName().getString(), WorldText.NAME_MAX) : null;
    }

    private static Map<String, Integer> blocksAround(ServerLevel world, BlockPos center) {
        Map<String, Integer> counts = new HashMap<>();
        BlockPos from = center.offset(-BLOCK_RADIUS, -BLOCK_RADIUS, -BLOCK_RADIUS);
        BlockPos to = center.offset(BLOCK_RADIUS, BLOCK_RADIUS, BLOCK_RADIUS);
        for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
            BlockState state = world.getBlockState(pos);
            if (!state.isAir()) counts.merge(blockId(state), 1, Integer::sum);
        }
        Map<String, Integer> top = new LinkedHashMap<>();
        counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(MAX_BLOCK_TYPES).forEach(e -> top.put(e.getKey(), e.getValue()));
        return top;
    }

    /** Containers, ender chests, crafting tables and beds (head part) in the same cube, nearest first. */
    private static List<StateSnapshot.Poi> pointsAround(ServerLevel world, BlockPos center) {
        List<BlockPos> found = new ArrayList<>();
        BlockPos from = center.offset(-BLOCK_RADIUS, -BLOCK_RADIUS, -BLOCK_RADIUS);
        BlockPos to = center.offset(BLOCK_RADIUS, BLOCK_RADIUS, BLOCK_RADIUS);
        for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
            BlockState state = world.getBlockState(pos);
            if (!state.isAir() && notable(world, pos, state)) found.add(pos.immutable());
        }
        found.sort(Comparator.comparingDouble(pos -> pos.distSqr(center)));
        return found.stream().limit(MAX_POINTS)
                .map(pos -> new StateSnapshot.Poi(BuiltInRegistries.BLOCK.getKey(world.getBlockState(pos).getBlock()).toString(), pos.getX(), pos.getY(), pos.getZ()))
                .toList();
    }

    private static boolean notable(ServerLevel world, BlockPos pos, BlockState state) {
        String id = blockId(state);
        if (id.endsWith("_bed")) return state.getBlock() instanceof BedBlock && state.getValue(BedBlock.PART) == BedPart.HEAD;
        if (id.equals("ender_chest") || id.equals("crafting_table")) return true;
        BlockEntity entity = state.hasBlockEntity() ? world.getBlockEntity(pos) : null;
        return entity instanceof Container;
    }

    private static String shortId(ResourceLocation id) {
        return "minecraft".equals(id.getNamespace()) ? id.getPath() : id.toString();
    }

    private static double horizontal(Entity from, Entity to) {
        return Reflexes.horizontal(to.getX() - from.getX(), to.getZ() - from.getZ());
    }

    private static StateSnapshot.Vec vec(Vec3 v) {
        return new StateSnapshot.Vec(v.x, v.y, v.z);
    }
}
