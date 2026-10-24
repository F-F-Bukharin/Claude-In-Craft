package com.example.claudeplayer.body;

import com.example.claudeplayer.AgentRuntime;
import com.example.claudeplayer.core.act.Action;
import com.example.claudeplayer.core.act.ActionResult;
import com.example.claudeplayer.core.safety.Guardrails;
import com.example.claudeplayer.core.work.Farming;
import com.example.claudeplayer.core.work.Planting;
import java.util.Locale;
import javax.annotation.Nullable;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * till, plant and use_bucket: one use of an item on a block face within reach, the vanilla way (the body looks at
 * the face, then the game mode uses the item: sounds, hoe wear, bucket swaps). Planting picks the failure codes;
 * Guardrails.checkPour decides where fluids may go. harvest reuses {@link #ripe}, {@link #useOn}, {@link #sow} and
 * {@link #sowOn}.
 */
final class FarmActions {
    private final AgentRuntime rt;

    FarmActions(AgentRuntime rt) {
        this.rt = rt;
    }

    /** Holds the first hoe carried and uses it on the block's top face; the hoe does the rest (farmland or dirt, sound, durability). */
    static ActionResult till(AgentPlayer p, BlockPos pos) {
        Inventory inv = p.getInventory();
        int slot = -1;
        for (int i = 0; i < inv.items.size() && slot < 0; i++) {
            if (inv.items.get(i).getItem() instanceof HoeItem) slot = i;
        }
        if (slot < 0) return ActionResult.failed("no_hoe");
        if (!ActionExecutor.inBlockReach(p, pos)) return ActionResult.failed("too_far");
        ServerLevel level = p.serverLevel();
        BlockState before = level.getBlockState(pos);
        String fail = Planting.tillFailure(SnapshotBuilder.blockId(before), level.getBlockState(pos.above()).isAir());
        if (fail != null) return ActionResult.failed(fail);
        Hands.hold(inv, slot);
        InteractionResult result = useOn(p, pos, Direction.UP, false);
        BlockState after = level.getBlockState(pos);
        if (!result.consumesAction() || after.equals(before)) return ActionResult.failed("not_tilled");
        p.swing(InteractionHand.MAIN_HAND, true);
        return ActionResult.ok("tilled " + at(pos) + ": now " + SnapshotBuilder.blockId(after));
    }

    /** One item from the inventory into the block above the soil, if Planting says the soil takes it. */
    static ActionResult plant(AgentPlayer p, Action.Plant a) {
        BlockPos soil = new BlockPos(a.x(), a.y(), a.z());
        String item = shortId(a.item());
        if (!Planting.plantable(item)) return ActionResult.failed("not_plantable");
        int slot = Hands.find(p.getInventory(), item);
        if (slot < 0) return ActionResult.failed("no_item");
        if (!ActionExecutor.inBlockReach(p, soil)) return ActionResult.failed("too_far");
        ServerLevel level = p.serverLevel();
        BlockState above = level.getBlockState(soil.above());
        boolean free = above.isAir() || above.canBeReplaced() && above.getFluidState().isEmpty();
        String fail = Planting.plantFailure(item, SnapshotBuilder.blockId(level.getBlockState(soil)), free, waterBeside(level, soil));
        if (fail != null) return ActionResult.failed(fail);
        if (!sow(p, soil, slot)) return ActionResult.failed("not_planted");
        return ActionResult.ok("planted " + item + " at " + at(soil.above()));
    }

    /** Plants the item in this slot on the soil's top face: {@link #sowOn} with UP. */
    static boolean sow(ServerPlayer p, BlockPos soil, int slot) {
        return sowOn(p, soil, Direction.UP, slot);
    }

    /**
     * Holds the item in this slot and uses it on that face of {@code against}, sneaking so the block itself isn't
     * used; true if a block now stands at {@code against.relative(face)} that wasn't there before.
     */
    static boolean sowOn(ServerPlayer p, BlockPos against, Direction face, int slot) {
        BlockPos spot = against.relative(face);
        ServerLevel level = p.serverLevel();
        BlockState before = level.getBlockState(spot);
        Hands.hold(p.getInventory(), slot);
        InteractionResult result = useOn(p, against, face, true);
        BlockState after = level.getBlockState(spot);
        if (!result.consumesAction() || after.isAir() || after.equals(before)) return false;
        p.swing(InteractionHand.MAIN_HAND, true);
        return true;
    }

    /** Looks at the centre of that face, then uses the main-hand item on it through the game mode (Forge's events included). */
    static InteractionResult useOn(ServerPlayer p, BlockPos pos, Direction face, boolean sneak) {
        Vec3 hitAt = Vec3.atCenterOf(pos).relative(face, 0.5);
        p.lookAt(EntityAnchorArgument.Anchor.EYES, hitAt);
        p.setShiftKeyDown(sneak);
        try {
            return p.gameMode.useItemOn(p, p.serverLevel(), p.getMainHandItem(), InteractionHand.MAIN_HAND,
                    new BlockHitResult(hitAt, face, pos, false));
        } finally {
            p.setShiftKeyDown(false);
        }
    }

    /**
     * Ripe by age, from the state alone (what {@code see} can tell): crops at max age, nether wart at 3, cocoa at 2,
     * a berry bush at 3. Melons, pumpkins and column crops need their neighbours: HarvestHandle#ripe adds them.
     */
    static boolean ripe(BlockState state) {
        String id = SnapshotBuilder.blockId(state);
        Block block = state.getBlock();
        if (block instanceof CropBlock crop) return Farming.ageRipe(id, crop.getAge(state), crop.getMaxAge());
        if (block instanceof NetherWartBlock) return Farming.ageRipe(id, state.getValue(NetherWartBlock.AGE), NetherWartBlock.MAX_AGE);
        if (block instanceof CocoaBlock) return Farming.ageRipe(id, state.getValue(CocoaBlock.AGE), CocoaBlock.MAX_AGE);
        if (block instanceof SweetBerryBushBlock) return Farming.ageRipe(id, state.getValue(SweetBerryBushBlock.AGE), SweetBerryBushBlock.MAX_AGE);
        return false;
    }

    /**
     * Fills an empty bucket from the source a player aiming at the position would take, or pours a carried water or
     * lava bucket onto the position's face the way BucketItem#use does. With {@code fluid} ("water" or "lava") only
     * that kind is poured or filled; without it, water first when both are carried. Guardrails.checkPour runs on the
     * block the fluid really goes into.
     */
    ActionResult useBucket(AgentPlayer p, BlockPos pos, @Nullable String fluid) {
        Inventory inv = p.getInventory();
        int empty = slotOf(inv, Items.BUCKET);
        int water = slotOf(inv, Items.WATER_BUCKET);
        int lava = slotOf(inv, Items.LAVA_BUCKET);
        if (empty < 0 && water < 0 && lava < 0) return ActionResult.failed("no_bucket");
        if (!ActionExecutor.inBlockReach(p, pos)) return ActionResult.failed("too_far");
        TagKey<Fluid> kind = fluid == null ? null : "lava".equals(fluid) ? FluidTags.LAVA : FluidTags.WATER;
        int full = fluid == null ? (water >= 0 ? water : lava) : "lava".equals(fluid) ? lava : water;
        if (empty >= 0) {
            // a source on the eye-line only wins over a pour when the position itself is a source (the surface of a deep pool)
            BlockPos source = sourceAimedAt(p, pos, kind);
            if (source != null && (full < 0 || pickable(p.serverLevel(), pos, kind))) return fill(p, source, empty);
        }
        if (full < 0) return ActionResult.failed(empty >= 0 ? "no_source" : "no_bucket");
        return pour(p, pos, full, full == lava);
    }

    /**
     * The source a player aiming at pos fills from: the first source on the line from the eyes to it (an empty
     * bucket's SOURCE_ONLY ray), else pos itself; of that kind when one is asked for. Flowing fluid never counts.
     */
    private static @Nullable BlockPos sourceAimedAt(ServerPlayer p, BlockPos pos, @Nullable TagKey<Fluid> kind) {
        ServerLevel level = p.serverLevel();
        BlockHitResult ray = level.clip(new ClipContext(p.getEyePosition(), Vec3.atCenterOf(pos), ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, p));
        if (ray.getType() == HitResult.Type.BLOCK && pickable(level, ray.getBlockPos(), kind) && ActionExecutor.inBlockReach(p, ray.getBlockPos())) {
            return ray.getBlockPos();
        }
        return pickable(level, pos, kind) ? pos : null;
    }

    /** A still source (water, lava, or a waterlogged block) a bucket can take, of this kind if one is given. */
    private static boolean pickable(Level level, BlockPos pos, @Nullable TagKey<Fluid> kind) {
        BlockState state = level.getBlockState(pos);
        return state.getFluidState().isSource() && state.getBlock() instanceof BucketPickup && (kind == null || state.getFluidState().is(kind));
    }

    private static ActionResult fill(AgentPlayer p, BlockPos pos, int slot) {
        ServerLevel level = p.serverLevel();
        BlockState state = level.getBlockState(pos);
        BucketPickup pickup = (BucketPickup) state.getBlock();
        Inventory inv = p.getInventory();
        Hands.hold(inv, slot);
        p.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(pos));
        ItemStack filled = pickup.pickupBlock(level, pos, state);
        if (filled.isEmpty()) return ActionResult.failed("no_source");
        pickup.getPickupSound(state).ifPresent(s -> level.playSound(null, pos, s, SoundSource.BLOCKS, 1f, 1f));
        level.gameEvent(p, GameEvent.FLUID_PICKUP, pos);
        inv.items.set(inv.selected, ItemUtils.createFilledResult(inv.items.get(inv.selected), p, filled)); // empty buckets stack: the rest stay
        p.swing(InteractionHand.MAIN_HAND, true);
        return ActionResult.ok("filled " + SnapshotBuilder.itemId(filled) + " at " + at(pos));
    }

    /**
     * Uses the bucket on the face of pos that faces the eyes. Like BucketItem#use, the fluid goes into pos if it can
     * take it (air, a replaceable block, or a block water can waterlog), else into the space beside that face. A
     * replaceable block the fluid would destroy must be natural or Claude's own, else denied:protected_block.
     */
    private ActionResult pour(AgentPlayer p, BlockPos pos, int slot, boolean lava) {
        ServerLevel level = p.serverLevel();
        Inventory inv = p.getInventory();
        BucketItem bucket = (BucketItem) inv.items.get(slot).getItem();
        Fluid content = bucket.getFluid();
        Direction face = faceToward(p, pos);
        Planting.PourInto atPos = pourInto(level, pos, content);
        BlockPos target = Planting.pourAt(atPos.clicksInto()) == Planting.PourAt.CLICKED ? pos : pos.relative(face);
        Planting.PourInto into = target.equals(pos) ? atPos : pourInto(level, target, content);
        if (into == Planting.PourInto.NO) return ActionResult.failed("not_poured");
        if (into == Planting.PourInto.PROTECTED) return ActionResult.denied("protected_block");
        Vec3 center = Vec3.atCenterOf(target);
        BlockPos home = rt.data().home();
        double fromHome = home == null || !level.dimension().equals(rt.data().homeDimension())
                ? Double.POSITIVE_INFINITY : center.distanceTo(Vec3.atCenterOf(home));
        double nearestPlayer = Double.POSITIVE_INFINITY;
        for (ServerPlayer other : level.players()) {
            if (other != p) nearestPlayer = Math.min(nearestPlayer, Math.sqrt(other.distanceToSqr(center)));
        }
        boolean ownBlock = p.getBoundingBox().intersects(new AABB(target)); // feet or head
        BlockPos feet = p.blockPosition();
        ActionResult rule = Guardrails.checkPour(lava, ownBlock, target.getX() - feet.getX(), target.getY() - feet.getY(),
                target.getZ() - feet.getZ(), fromHome, nearestPlayer, level.dimensionType().ultraWarm());
        if (rule.isError()) return rule;
        Hands.hold(inv, slot);
        ItemStack stack = inv.getSelected();
        Vec3 hitAt = Vec3.atCenterOf(pos).relative(face, 0.5);
        p.lookAt(EntityAnchorArgument.Anchor.EYES, hitAt);
        if (!bucket.emptyContents(p, level, target, new BlockHitResult(hitAt, face, pos, false), stack)) return ActionResult.failed("not_poured");
        inv.items.set(inv.selected, BucketItem.getEmptySuccessItem(stack, p));
        p.swing(InteractionHand.MAIN_HAND, true);
        return ActionResult.ok("poured " + (lava ? "lava" : "water") + " at " + at(target)
                + (target.equals(pos) ? "" : " (beside the " + face.getName() + " face of " + at(pos) + ")") + "; kept the empty bucket");
    }

    /**
     * What the block here does with this fluid from a bucket (Planting.pourInto): air and fluid blocks take it, so does
     * a LiquidBlockContainer that can hold it (waterlogging); a block the fluid would replace, and so destroy, only
     * when it is natural or Claude placed it (the mining rule).
     */
    private Planting.PourInto pourInto(Level level, BlockPos pos, Fluid fluid) {
        BlockState state = level.getBlockState(pos);
        boolean waterlogs = state.getBlock() instanceof LiquidBlockContainer c && c.canPlaceLiquid(level, pos, state, fluid);
        boolean replaceable = state.canBeReplaced(fluid);
        boolean natural = replaceable && NaturalBlocks.is(state);
        boolean selfPlaced = replaceable && SnapshotBuilder.blockId(state).equals(rt.data().placedBlock(level.dimension(), pos));
        return Planting.pourInto(state.isAir() || state.getBlock() instanceof LiquidBlock, waterlogs, replaceable, natural, selfPlaced);
    }

    /** The face a player sees: the face the eye-line hits, or the face pointing most toward the eyes when something is in the way. */
    private static Direction faceToward(ServerPlayer p, BlockPos pos) {
        Vec3 eye = p.getEyePosition();
        Vec3 c = Vec3.atCenterOf(pos);
        BlockHitResult ray = p.serverLevel().clip(new ClipContext(eye, c, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
        if (ray.getType() == HitResult.Type.BLOCK && ray.getBlockPos().equals(pos)) return ray.getDirection();
        return Direction.getNearest(eye.x - c.x, eye.y - c.y, eye.z - c.z);
    }

    /** Water (any water fluid, as canBeHydrated checks) or frosted ice beside the soil, as sugar cane needs. */
    private static boolean waterBeside(Level level, BlockPos soil) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos side = soil.relative(d);
            if (level.getFluidState(side).is(FluidTags.WATER) || level.getBlockState(side).is(Blocks.FROSTED_ICE)) return true;
        }
        return false;
    }

    private static String shortId(String item) {
        String id = item.toLowerCase(Locale.ROOT);
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    private static int slotOf(Inventory inv, Item want) {
        for (int i = 0; i < inv.items.size(); i++) {
            if (inv.items.get(i).is(want)) return i;
        }
        return -1;
    }

    static String at(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
