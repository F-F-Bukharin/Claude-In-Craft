package com.example.claudeplayer.body;

import com.example.claudeplayer.AgentRuntime;
import com.example.claudeplayer.core.act.Action;
import com.example.claudeplayer.core.act.ActionResult;
import com.example.claudeplayer.core.act.ActionRunner;
import com.example.claudeplayer.core.safety.CautionPolicy;
import java.util.List;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Runs one world action on the body, on the server thread. Checks come before effects (guardrails, then the
 * caution policy for attacks), so a denial reaches the model with its reason instead of the world changing.
 * Memory tools never get here; MemoryActions handles them in the core.
 */
public final class ActionExecutor implements ActionRunner {
    public static final double ATTACK_REACH = 3.0;
    static final double CONTAINER_REACH = 6.0;
    private static final double BLOCK_REACH = 4.5;
    /** Faces to place against, the floor first. */
    private static final List<Direction> SUPPORT = List.of(Direction.DOWN, Direction.NORTH, Direction.SOUTH,
            Direction.WEST, Direction.EAST, Direction.UP);

    private final AgentRuntime rt;
    private final ContainerActions containers;
    private final FarmActions farm;

    public ActionExecutor(AgentRuntime rt) {
        this.rt = rt;
        this.containers = new ContainerActions(rt);
        this.farm = new FarmActions(rt);
    }

    @Override
    public Handle start(Action action) {
        AgentPlayer p = rt.body().player();
        if (p == null) return Handle.done(ActionResult.failed("no_body"));
        if (action instanceof Action.GetState) return Handle.done(ActionResult.ok(SnapshotBuilder.build(rt).toJson()));
        if (action instanceof Action.Look look) {
            return Handle.done(ActionResult.ok(SnapshotBuilder.map(p, look.radius(), rt.settings().hostileRange()).text()));
        }
        if (action instanceof Action.Say say) {
            rt.say(say.text());
            return Handle.done(ActionResult.ok());
        }
        if (action instanceof Action.Wait wait) return waitFor((int) Math.round(wait.seconds() * 20));
        if (action instanceof Action.Stop) {
            rt.navigator().stop(p);
            return Handle.done(ActionResult.ok());
        }
        if (action instanceof Action.MoveTo m) return rt.navigator().moveTo(p, new Vec3(m.x(), m.y(), m.z()));
        if (action instanceof Action.Flee) return flee(p);
        if (action instanceof Action.InspectContainer c) return Handle.done(containers.inspect(p, new BlockPos(c.x(), c.y(), c.z())));
        if (action instanceof Action.Store s) return Handle.done(containers.store(p, s));
        if (action instanceof Action.Take t) return Handle.done(containers.take(p, t));
        if (action instanceof Action.Eat e) return EatHandle.start(p, e.auto());
        if (action instanceof Action.FollowPlayer f) return follow(p, f);
        if (action instanceof Action.AttackEntity a) return Handle.done(attack(p, a.entityId()));
        if (action instanceof Action.Fight f) return FightHandle.start(rt, p, f);
        if (action instanceof Action.MineBlock b) return MineHandle.start(rt, p, new BlockPos(b.x(), b.y(), b.z()), true);
        if (action instanceof Action.PlaceBlock b) return Handle.done(place(p, new BlockPos(b.x(), b.y(), b.z()), b.item()));
        if (action instanceof Action.Equip e) return Handle.done(equip(p, e.item()));
        if (action instanceof Action.Craft c) return Crafting.start(p, c);
        if (action instanceof Action.Smelt s) return Handle.done(Smelting.load(rt, p, s));
        if (action instanceof Action.Gather g) return GatherHandle.start(rt, p, g);
        if (action instanceof Action.Dig d) return new DigHandle(rt, p, d);
        if (action instanceof Action.Till t) return Handle.done(FarmActions.till(p, new BlockPos(t.x(), t.y(), t.z())));
        if (action instanceof Action.Plant pl) return Handle.done(FarmActions.plant(p, pl));
        if (action instanceof Action.Harvest h) return new HarvestHandle(rt, p, h);
        if (action instanceof Action.UseBucket b) return Handle.done(farm.useBucket(p, new BlockPos(b.x(), b.y(), b.z())));
        return Handle.done(ActionResult.failed("not_implemented"));
    }

    private static Handle waitFor(int ticks) {
        return new Handle() {
            private int left = Math.max(1, ticks);

            @Override
            public ActionResult poll() {
                return --left <= 0 ? ActionResult.ok() : null;
            }

            @Override
            public int timeoutTicks() {
                return ticks + 20;
            }
        };
    }

    private Handle follow(AgentPlayer p, Action.FollowPlayer f) {
        ServerPlayer target = rt.server().getPlayerList().getPlayerByName(f.name());
        if (target == null || target == p) return Handle.done(ActionResult.failed("no_such_player"));
        return rt.navigator().follow(p, target, (int) Math.round(f.seconds() * 20));
    }

    /** Runs to the first reachable spot on the side away from the nearest hostile. */
    private Handle flee(AgentPlayer p) {
        List<Mob> threats = SnapshotBuilder.hostiles(p, rt.settings().hostileRange());
        if (threats.isEmpty()) return Handle.done(ActionResult.ok("no_threats"));
        Vec3 dest = rt.escape().destination(p, threats);
        if (dest == null) return Handle.done(ActionResult.failed("no_escape"));
        return rt.navigator().moveTo(p, dest);
    }

    private ActionResult attack(AgentPlayer p, int entityId) {
        Entity target = p.serverLevel().getEntity(entityId);
        if (target == null || !target.isAlive()) return ActionResult.failed("no_target");
        double distance = p.distanceTo(target);
        CautionPolicy.Situation situation = new CautionPolicy.Situation(SnapshotBuilder.isHostile(target),
                target instanceof Player, distance, distance <= 2.0, p.getHealth() / p.getMaxHealth(),
                rt.escape().escapeExists(p, SnapshotBuilder.hostiles(p, rt.settings().hostileRange())),
                rt.damage().hurtWhileFleeing());
        CautionPolicy.Verdict verdict = CautionPolicy.check(rt.level(), situation);
        if (!verdict.allowed()) return ActionResult.denied(verdict.reason());
        if (distance > ATTACK_REACH) return ActionResult.failed("out_of_reach");
        if (p.getAttackStrengthScale(0.5f) < 0.9f) return ActionResult.failed("cooldown");
        p.lookAt(EntityAnchorArgument.Anchor.EYES, target.getEyePosition());
        p.attack(target);
        p.swing(InteractionHand.MAIN_HAND, true);
        return ActionResult.ok();
    }

    static boolean inBlockReach(ServerPlayer p, BlockPos pos) {
        return p.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) <= BLOCK_REACH;
    }

    /** Places the way a player does (sneaking, so a chest or door it builds against doesn't open), then records the block as Claude's. */
    private ActionResult place(AgentPlayer p, BlockPos pos, String item) {
        Inventory inv = p.getInventory();
        int slot = Hands.find(inv, item);
        if (slot < 0) return ActionResult.failed("missing_item");
        if (!(inv.items.get(slot).getItem() instanceof BlockItem blockItem)) return ActionResult.failed("not_a_block");
        if (!inBlockReach(p, pos)) return ActionResult.failed("out_of_reach");
        ServerLevel level = p.serverLevel();
        BlockState before = level.getBlockState(pos);
        if (!before.canBeReplaced()) return ActionResult.failed("occupied");
        Direction against = SUPPORT.stream().filter(d -> {
            BlockPos n = pos.relative(d);
            BlockState s = level.getBlockState(n);
            return !s.canBeReplaced() && !s.getShape(level, n).isEmpty();
        }).findFirst().orElse(null);
        if (against == null) return ActionResult.failed("no_support");
        if (!level.isUnobstructed(blockItem.getBlock().defaultBlockState(), pos, CollisionContext.empty())) return ActionResult.failed("blocked");
        String itemId = SnapshotBuilder.itemId(inv.items.get(slot));
        Hands.hold(inv, slot);
        Vec3 hitAt = Vec3.atCenterOf(pos).relative(against, 0.5);
        p.lookAt(EntityAnchorArgument.Anchor.EYES, hitAt);
        p.setShiftKeyDown(true);
        InteractionResult result;
        try {
            result = p.gameMode.useItemOn(p, level, p.getMainHandItem(), InteractionHand.MAIN_HAND,
                    new BlockHitResult(hitAt, against.getOpposite(), pos.relative(against), false));
        } finally {
            p.setShiftKeyDown(false);
        }
        BlockState after = level.getBlockState(pos);
        if (!result.consumesAction() || after.equals(before) || after.isAir()) return ActionResult.failed("not_placed");
        rt.data().addPlaced(level.dimension(), pos, SnapshotBuilder.blockId(after));
        p.swing(InteractionHand.MAIN_HAND, true);
        return ActionResult.ok("placed " + itemId + " at " + pos.getX() + "," + pos.getY() + "," + pos.getZ());
    }

    /** Armor goes on its slot; anything else to the main hand. Whatever was there swaps into the item's old slot. */
    private static ActionResult equip(AgentPlayer p, String item) {
        Inventory inv = p.getInventory();
        int slot = Hands.find(inv, item);
        if (slot < 0) return ActionResult.failed("missing_item");
        ItemStack stack = inv.items.get(slot);
        String id = SnapshotBuilder.itemId(stack);
        EquipmentSlot wear = LivingEntity.getEquipmentSlotForItem(stack);
        if (wear.getType() == EquipmentSlot.Type.ARMOR) {
            inv.items.set(slot, p.getItemBySlot(wear));
            p.setItemSlot(wear, stack);
            return ActionResult.ok("wearing " + id);
        }
        Hands.hold(inv, slot);
        return ActionResult.ok("holding " + id);
    }
}
