package com.example.claudeplayer.body;

import com.example.claudeplayer.AgentRuntime;
import com.example.claudeplayer.core.StateSnapshot;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Builds the immutable {@link StateSnapshot} from the live world, plus the lookups other game code shares. */
public final class SnapshotBuilder {
    private static final int BLOCK_RADIUS = 3;
    private static final int MAX_MOBS = 10;
    private static final int MAX_PLAYERS = 10;
    private static final int MAX_SLOTS = 12;
    private static final int MAX_BLOCK_TYPES = 12;

    private SnapshotBuilder() {}

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
                .map(o -> new StateSnapshot.Seen(WorldText.clean(o.getGameProfile().getName(), WorldText.NAME_MAX), p.distanceTo(o)))
                .toList();
        String weather = world.isThundering() ? "thunder" : world.isRaining() ? "rain" : "clear";
        return new StateSnapshot(true, false, vec(p.position()), world.dimension().location().toString(),
                p.getDirection().getSerializedName(), p.getHealth(), p.getMaxHealth(), p.getFoodData().getFoodLevel(),
                p.getMainHandItem().isEmpty() ? "empty" : itemId(p.getMainHandItem()), armor, inventory, hostiles, players,
                blocksAround(world, p.blockPosition()), world.getDayTime() % 24000, weather, level, home,
                rt.queue().runningTool(), rt.queue().waitingCount());
    }

    /** Monster-category mobs within range of the body, nearest first. */
    public static List<Mob> hostiles(ServerPlayer body, double range) {
        List<Mob> mobs = new ArrayList<>(body.serverLevel().getEntitiesOfClass(Mob.class, body.getBoundingBox().inflate(range),
                m -> m.isAlive() && isHostile(m) && m.distanceTo(body) <= range));
        mobs.sort(Comparator.comparingDouble(body::distanceToSqr));
        return mobs;
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
