package com.example.claudeplayer.body;

import com.example.claudeplayer.AgentRuntime;
import com.example.claudeplayer.core.act.Action;
import com.example.claudeplayer.core.act.ActionResult;
import com.example.claudeplayer.core.act.ActionRunner;
import com.example.claudeplayer.core.work.GatherRule;
import com.example.claudeplayer.core.work.RoamPlan;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * roam: walks out toward the directions least seen (RoamPlan picks the waypoints), looks around every few seconds and
 * writes what it sees as short-term notes (trees, ores in the open or at cave mouths, water, lava, villages, animals
 * by kind), turns back at half the time, and reports once at the start: ok with the finds. A waypoint it can't reach
 * is skipped; when it gives up with none reachable at all it ends failed:no_path (time running out before the first
 * waypoint is still an ok roam: it walks back and reports what it saw); a hostile close by ends it failed:danger with the
 * finds so far. It never mines, places or attacks, and says nothing per step.
 */
final class RoamHandle implements ActionRunner.Handle {
    /** The look-around runs this often (3 s). */
    private static final int SCAN_INTERVAL = 60;
    /** Horizontal reach of a look-around, and the vertical band around the feet (blocks). */
    private static final int SCAN_RADIUS = 16;
    private static final int SCAN_DOWN = 8;
    private static final int SCAN_UP = 14;
    /** Block reads per look-around at most. The grid is every second block each way, shifted every scan. */
    private static final int MAX_CHECKS = 3000;
    /** A waypoint not reached in 40 s counts as unreachable. */
    private static final int WAYPOINT_TICKS = 800;
    /** Time allowed past the roam's own length for the walk home (30 s). */
    private static final int HOME_MARGIN = 600;
    /** A waypoint whose chunk isn't loaded is pulled toward the start by these factors before giving up on it. */
    private static final double[] PULL_IN = {1.0, 0.7, 0.45};

    private final AgentRuntime rt;
    private final AgentPlayer body;
    private final BlockPos start;
    private final RoamPlan plan;
    private final Map<Block, String> kinds = new HashMap<>(); // block to what it is worth noting, "" for nothing
    private int ticks;
    private int scans;
    private boolean homing;
    private RoamPlan.Waypoint waypoint;
    private ActionRunner.Handle walk;
    private int walkTicks;

    private RoamHandle(AgentRuntime rt, AgentPlayer body, Action.Roam a) {
        this.rt = rt;
        this.body = body;
        this.start = body.blockPosition();
        this.plan = new RoamPlan(start.getX(), start.getY(), start.getZ(), a.radius(), a.minutes(), body.getRandom().nextInt(RoamPlan.SECTORS));
    }

    static ActionRunner.Handle start(AgentRuntime rt, AgentPlayer p, Action.Roam a) {
        return new RoamHandle(rt, p, a);
    }

    @Override
    public ActionResult poll() {
        if (!body.isAlive()) {
            cancel();
            return ActionResult.failed("died");
        }
        ticks++;
        List<Mob> near = SnapshotBuilder.hostiles(body, GatherRule.DANGER_RANGE);
        if (!near.isEmpty() || body.getHealth() / body.getMaxHealth() < GatherRule.LOW_HEALTH) {
            cancel();
            return ActionResult.failed("danger", plan.summary());
        }
        if ((ticks - 1) % SCAN_INTERVAL == 0) lookAround();
        if (!homing && plan.turnBack(ticks)) return goHome();
        return homing ? pollHome() : pollOut();
    }

    private ActionResult pollOut() {
        if (walk == null) {
            waypoint = plan.next();
            if (waypoint == null) return goHome();
            Vec3 target = target(waypoint);
            if (target == null) {
                plan.unreachable(waypoint);
                return null;
            }
            walk = rt.navigator().moveTo(body, target);
            walkTicks = 0;
            return null;
        }
        ActionResult r = walk.poll();
        if (r == null && ++walkTicks < WAYPOINT_TICKS) return null;
        walk = null;
        if (r != null && !r.isError()) {
            plan.reached(waypoint);
        } else {
            rt.navigator().stop(body);
            plan.unreachable(waypoint); // no_path, stuck, a hazard at the edge, or too slow: try another direction
        }
        return null;
    }

    /** Turns back to the start. no_path only when the plan gave up with nothing reached; time running out first is a roam. */
    private ActionResult goHome() {
        if (plan.noPath()) return noPath();
        homing = true;
        waypoint = null;
        walkTicks = 0;
        walk = rt.navigator().moveTo(body, Vec3.atBottomCenterOf(start));
        return null;
    }

    private ActionResult pollHome() {
        ActionResult r = walk.poll();
        if (r != null) return finish(r.isError() ? " (could not walk back to the start)" : "");
        if (ticks >= plan.totalTicks() + HOME_MARGIN) return finish(" (did not make it back to the start)");
        return null;
    }

    private ActionResult noPath() {
        cancel();
        return ActionResult.failed("no_path");
    }

    private ActionResult finish(String tail) {
        cancel();
        return ActionResult.ok(plan.summary() + tail);
    }

    /** Where to walk for a waypoint: the surface there, pulled toward the start while its chunk isn't loaded. Null if none. */
    private @Nullable Vec3 target(RoamPlan.Waypoint w) {
        ServerLevel level = body.serverLevel();
        for (double pull : PULL_IN) {
            int x = start.getX() + (int) Math.round((w.x() - start.getX()) * pull);
            int z = start.getZ() + (int) Math.round((w.z() - start.getZ()) * pull);
            if (!level.hasChunk(x >> 4, z >> 4)) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (y <= level.getMinBuildHeight() + 1) return null;
            return Vec3.atBottomCenterOf(new BlockPos(x, y, z));
        }
        return null;
    }

    /** One look-around: a sparse grid of blocks near the body (capped), then the animals and villagers. Notes each find. */
    private void lookAround() {
        ServerLevel level = body.serverLevel();
        BlockPos at = body.blockPosition();
        plan.visit(at.getX(), at.getZ());
        int ox = scans & 1;
        int oz = scans >> 1 & 1;
        int oy = scans >> 2 & 1;
        scans++;
        int checks = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = -SCAN_RADIUS + ox; dx <= SCAN_RADIUS && checks < MAX_CHECKS; dx += 2) {
            for (int dz = -SCAN_RADIUS + oz; dz <= SCAN_RADIUS && checks < MAX_CHECKS; dz += 2) {
                int x = at.getX() + dx;
                int z = at.getZ() + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                for (int dy = -SCAN_DOWN + oy; dy <= SCAN_UP; dy += 2) {
                    int y = at.getY() + dy;
                    if (level.isOutsideBuildHeight(y)) continue;
                    checks++;
                    pos.set(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) continue;
                    String kind = kind(state);
                    if (kind.isEmpty() || isOre(kind) && !openToAir(level, pos)) continue;
                    note(kind, x, y, z, 1);
                }
            }
        }
        AABB box = body.getBoundingBox().inflate(SCAN_RADIUS, SCAN_DOWN, SCAN_RADIUS);
        Map<String, Integer> herds = new HashMap<>();
        Map<String, BlockPos> nearest = new HashMap<>();
        for (Animal animal : level.getEntitiesOfClass(Animal.class, box, e -> e.isAlive() && !(e instanceof TamableAnimal t && t.isTame()))) {
            String kind = SnapshotBuilder.typeId(animal);
            herds.merge(kind, 1, Integer::sum);
            BlockPos p = animal.blockPosition();
            BlockPos old = nearest.get(kind);
            if (old == null || p.distSqr(at) < old.distSqr(at)) nearest.put(kind, p);
        }
        for (Map.Entry<String, Integer> e : herds.entrySet()) {
            BlockPos p = nearest.get(e.getKey());
            note(e.getKey(), p.getX(), p.getY(), p.getZ(), e.getValue());
        }
        for (Villager v : level.getEntitiesOfClass(Villager.class, box, Villager::isAlive)) {
            note("village", v.getBlockX(), v.getBlockY(), v.getBlockZ(), 1);
        }
    }

    private void note(String kind, int x, int y, int z, int count) {
        RoamPlan.Note n = plan.see(kind, x, y, z, count);
        if (n != null) rt.memory().note(n.subject(), n.text());
    }

    /** What a block is worth a note as: tree, water, lava, village (a bell), an ore's id; "" for the rest. Cached per block. */
    private String kind(BlockState state) {
        return kinds.computeIfAbsent(state.getBlock(), b -> {
            if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)) return "tree";
            if (state.is(Blocks.WATER)) return "water";
            if (state.is(Blocks.LAVA)) return "lava";
            if (state.is(Blocks.BELL)) return "village";
            String id = SnapshotBuilder.blockId(state);
            return isOre(id) ? id : "";
        });
    }

    private static boolean isOre(String id) {
        return id.endsWith("_ore") || id.equals("ancient_debris");
    }

    /** At least one side is air, so the ore shows on the surface or at a cave opening rather than buried in rock. */
    private static boolean openToAir(ServerLevel level, BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            if (level.isLoaded(n) && level.getBlockState(n).isAir()) return true;
        }
        return false;
    }

    @Override
    public void cancel() {
        if (walk != null) walk.cancel();
        walk = null;
        rt.navigator().stop(body);
    }

    /** Its own clock ends the run with the report first; this is the backstop, past the walk home. */
    @Override
    public int timeoutTicks() {
        return plan.totalTicks() + HOME_MARGIN + 100;
    }
}
