package com.example.claudeplayer.body;

import com.example.claudeplayer.AgentRuntime;
import com.example.claudeplayer.core.act.ActionResult;
import java.util.List;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Places one block the way a player does. Shared by place_block and the tools that build (build, build_house). */
final class Placing {
    /** Faces to place against, the floor first. */
    private static final List<Direction> SUPPORT = List.of(Direction.DOWN, Direction.NORTH, Direction.SOUTH,
            Direction.WEST, Direction.EAST, Direction.UP);

    private Placing() {
    }

    /** Places the way a player does (sneaking, so a chest or door it builds against doesn't open), then records the block as Claude's. */
    static ActionResult place(AgentRuntime rt, AgentPlayer p, BlockPos pos, String item) {
        Inventory inv = p.getInventory();
        int slot = Hands.find(inv, item);
        if (slot < 0) return ActionResult.failed("missing_item");
        if (!(inv.items.get(slot).getItem() instanceof BlockItem blockItem)) return ActionResult.failed("not_a_block");
        if (!ActionExecutor.inBlockReach(p, pos)) return ActionResult.failed("out_of_reach");
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
}
