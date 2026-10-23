package com.example.claudeplayer.body;

import com.example.claudeplayer.AgentRuntime;
import com.example.claudeplayer.core.act.Action;
import com.example.claudeplayer.core.act.ActionResult;
import com.example.claudeplayer.core.safety.Guardrails;
import com.example.claudeplayer.core.work.Farming;
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
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * till, plant and use_bucket: one use of an item on a block within reach, the vanilla way (sounds, hoe wear,
 * bucket swaps). Farming decides soils; Guardrails.checkPour decides where fluids may go. harvest reuses
 * {@link #ripe} and {@link #sow}.
 */
final class FarmActions {
    private final AgentRuntime rt;

    FarmActions(AgentRuntime rt) {
        this.rt = rt;
    }

    /** Holds the first hoe carried and uses it on the block's top face; HoeItem does the rest (farmland, sound, durability). */
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
        if (!Farming.tillable(SnapshotBuilder.blockId(before))) return ActionResult.failed("not_tillable");
        if (!level.getBlockState(pos.above()).isAir()) return ActionResult.failed("blocked");
        Hands.hold(inv, slot);
        InteractionResult result = useOnTop(p, pos, false);
        BlockState after = level.getBlockState(pos);
        if (!result.consumesAction() || after.equals(before)) return ActionResult.failed("not_tilled");
        p.swing(InteractionHand.MAIN_HAND, true);
        return ActionResult.ok("tilled " + at(pos) + ": now " + SnapshotBuilder.blockId(after));
    }

    /** One item from the inventory into the block above the soil, if Farming.fits says the soil takes it. */
    static ActionResult plant(AgentPlayer p, Action.Plant a) {
        BlockPos soil = new BlockPos(a.x(), a.y(), a.z());
        int slot = Hands.find(p.getInventory(), a.item());
        if (slot < 0) return ActionResult.failed("no_item");
        if (!ActionExecutor.inBlockReach(p, soil)) return ActionResult.failed("too_far");
        ServerLevel level = p.serverLevel();
        if (!Farming.fits(a.item(), SnapshotBuilder.blockId(level.getBlockState(soil)))) return ActionResult.failed("wrong_soil");
        if (!level.getBlockState(soil.above()).isAir()) return ActionResult.failed("occupied");
        if (!sow(p, soil, slot)) return ActionResult.failed("not_planted");
        return ActionResult.ok("planted " + a.item() + " at " + at(soil.above()));
    }

    /** Holds the item in this slot and uses it on the soil's top face, sneaking so the soil itself isn't used; true if something now grows above. */
    static boolean sow(ServerPlayer p, BlockPos soil, int slot) {
        Hands.hold(p.getInventory(), slot);
        InteractionResult result = useOnTop(p, soil, true);
        if (!result.consumesAction() || p.serverLevel().getBlockState(soil.above()).isAir()) return false;
        p.swing(InteractionHand.MAIN_HAND, true);
        return true;
    }

    private static InteractionResult useOnTop(ServerPlayer p, BlockPos pos, boolean sneak) {
        Vec3 hitAt = Vec3.atCenterOf(pos).relative(Direction.UP, 0.5);
        p.lookAt(EntityAnchorArgument.Anchor.EYES, hitAt);
        p.setShiftKeyDown(sneak);
        try {
            return p.gameMode.useItemOn(p, p.serverLevel(), p.getMainHandItem(), InteractionHand.MAIN_HAND,
                    new BlockHitResult(hitAt, Direction.UP, pos, false));
        } finally {
            p.setShiftKeyDown(false);
        }
    }

    /** A crop harvest knows (Farming.seedFor) at its max age: CropBlock#isMaxAge (beetroot included) or nether wart's age 3. */
    static boolean ripe(BlockState state) {
        if (Farming.seedFor(SnapshotBuilder.blockId(state)) == null) return false;
        if (state.getBlock() instanceof CropBlock crop) return Farming.ripe(crop.getAge(state), crop.getMaxAge());
        if (state.getBlock() instanceof NetherWartBlock) return Farming.ripe(state.getValue(NetherWartBlock.AGE), NetherWartBlock.MAX_AGE);
        return false;
    }

    /**
     * Fills an empty bucket from a source at the position, or pours a carried water or lava bucket there. With
     * {@code fluid} ("water" or "lava") only that kind is poured, and a fill only takes that kind; without it, water
     * first when both are carried. Guardrails.checkPour runs before any pour.
     */
    ActionResult useBucket(AgentPlayer p, BlockPos pos, @Nullable String fluid) {
        Inventory inv = p.getInventory();
        int empty = slotOf(inv, Items.BUCKET);
        int water = slotOf(inv, Items.WATER_BUCKET);
        int lava = slotOf(inv, Items.LAVA_BUCKET);
        if (empty < 0 && water < 0 && lava < 0) return ActionResult.failed("no_bucket");
        if (!ActionExecutor.inBlockReach(p, pos)) return ActionResult.failed("too_far");
        ServerLevel level = p.serverLevel();
        BlockState state = level.getBlockState(pos);
        boolean source = state.getFluidState().isSource() && state.getBlock() instanceof BucketPickup;
        TagKey<Fluid> kind = fluid == null ? null : "lava".equals(fluid) ? FluidTags.LAVA : FluidTags.WATER;
        if (empty >= 0 && source && (kind == null || state.getFluidState().is(kind))) return fill(p, pos, state, empty);
        int full = fluid == null ? (water >= 0 ? water : lava) : "lava".equals(fluid) ? lava : water;
        if (full < 0) return ActionResult.failed(fluid == null ? "no_source" : "no_bucket");
        return pour(p, pos, full, full == lava);
    }

    private static ActionResult fill(AgentPlayer p, BlockPos pos, BlockState state, int slot) {
        ServerLevel level = p.serverLevel();
        BucketPickup pickup = (BucketPickup) state.getBlock();
        p.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(pos));
        ItemStack filled = pickup.pickupBlock(level, pos, state);
        if (filled.isEmpty()) return ActionResult.failed("no_source");
        pickup.getPickupSound(state).ifPresent(s -> level.playSound(null, pos, s, SoundSource.BLOCKS, 1f, 1f));
        level.gameEvent(p, GameEvent.FLUID_PICKUP, pos);
        Inventory inv = p.getInventory();
        inv.items.set(slot, ItemUtils.createFilledResult(inv.items.get(slot), p, filled)); // empty buckets stack: the rest stay
        p.swing(InteractionHand.MAIN_HAND, true);
        return ActionResult.ok("filled " + SnapshotBuilder.itemId(filled) + " at " + at(pos));
    }

    private ActionResult pour(AgentPlayer p, BlockPos pos, int slot, boolean lava) {
        ServerLevel level = p.serverLevel();
        Vec3 center = Vec3.atCenterOf(pos);
        BlockPos home = rt.data().home();
        double fromHome = home == null || !level.dimension().equals(rt.data().homeDimension())
                ? Double.POSITIVE_INFINITY : center.distanceTo(Vec3.atCenterOf(home));
        double nearestPlayer = Double.POSITIVE_INFINITY;
        for (ServerPlayer other : level.players()) {
            if (other != p) nearestPlayer = Math.min(nearestPlayer, Math.sqrt(other.distanceToSqr(center)));
        }
        boolean ownBlock = p.getBoundingBox().intersects(new AABB(pos)); // feet or head
        ActionResult rule = Guardrails.checkPour(lava, ownBlock, fromHome, nearestPlayer);
        if (rule.isError()) return rule;
        Inventory inv = p.getInventory();
        ItemStack stack = inv.items.get(slot);
        p.lookAt(EntityAnchorArgument.Anchor.EYES, center);
        if (!((BucketItem) stack.getItem()).emptyContents(p, level, pos, null, stack)) return ActionResult.failed("not_poured");
        inv.items.set(slot, BucketItem.getEmptySuccessItem(stack, p));
        p.swing(InteractionHand.MAIN_HAND, true);
        return ActionResult.ok("poured " + (lava ? "lava" : "water") + " at " + at(pos) + "; kept the empty bucket");
    }

    private static int slotOf(Inventory inv, Item want) {
        for (int i = 0; i < inv.items.size(); i++) {
            if (inv.items.get(i).is(want)) return i;
        }
        return -1;
    }

    private static String at(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
