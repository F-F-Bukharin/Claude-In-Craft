package com.example.claudeplayer.body;

import java.util.List;
import java.util.Set;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Which blocks count as natural for the break rule (Guardrails.checkBreak), by tag and id. Everything else is man-made. */
final class NaturalBlocks {
    private static final List<TagKey<Block>> TAGS = List.of(BlockTags.LOGS, BlockTags.BASE_STONE_OVERWORLD,
            BlockTags.BASE_STONE_NETHER, BlockTags.DIRT, BlockTags.SAND, BlockTags.COAL_ORES, BlockTags.COPPER_ORES,
            BlockTags.IRON_ORES, BlockTags.GOLD_ORES, BlockTags.REDSTONE_ORES, BlockTags.LAPIS_ORES,
            BlockTags.DIAMOND_ORES, BlockTags.EMERALD_ORES, BlockTags.SNOW, BlockTags.ICE, BlockTags.FLOWERS,
            BlockTags.REPLACEABLE_BY_TREES); // grass, ferns, vines, dead bushes and the like
    private static final Set<Block> BLOCKS = Set.of(Blocks.GRAVEL, Blocks.CLAY, Blocks.NETHER_QUARTZ_ORE,
            Blocks.ANCIENT_DEBRIS, Blocks.GRASS, Blocks.TALL_GRASS, Blocks.FERN, Blocks.LARGE_FERN, Blocks.DEAD_BUSH,
            Blocks.VINE, Blocks.BROWN_MUSHROOM, Blocks.RED_MUSHROOM);

    private NaturalBlocks() {}

    static boolean is(BlockState state) {
        // Leaves a player placed are persistent (hedges); trees grow non-persistent ones. Stripped logs are worked wood.
        if (state.is(BlockTags.LEAVES)) return !(state.hasProperty(LeavesBlock.PERSISTENT) && state.getValue(LeavesBlock.PERSISTENT));
        if (state.is(BlockTags.LOGS) && SnapshotBuilder.blockId(state).contains("stripped_")) return false;
        return BLOCKS.contains(state.getBlock()) || TAGS.stream().anyMatch(state::is);
    }
}
