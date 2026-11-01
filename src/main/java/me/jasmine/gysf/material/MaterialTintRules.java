package me.jasmine.gysf.material;

import java.util.Set;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Known vanilla tint meanings, shared by world, moving blocks, displays and inventory items. */
public final class MaterialTintRules {
    private static final Set<Block> DECORATIVE = Set.of(
        Blocks.GRASS_BLOCK, Blocks.SHORT_GRASS, Blocks.TALL_GRASS, Blocks.FERN, Blocks.LARGE_FERN,
        Blocks.POTTED_FERN, Blocks.BUSH, Blocks.PINK_PETALS, Blocks.WILDFLOWERS, Blocks.VINE,
        Blocks.OAK_LEAVES, Blocks.SPRUCE_LEAVES, Blocks.BIRCH_LEAVES, Blocks.JUNGLE_LEAVES,
        Blocks.ACACIA_LEAVES, Blocks.DARK_OAK_LEAVES, Blocks.MANGROVE_LEAVES,
        Blocks.LEAF_LITTER, Blocks.SUGAR_CANE, Blocks.LILY_PAD
    );

    private MaterialTintRules() {}

    public static boolean decorative(Block block) {
        return DECORATIVE.contains(block);
    }

    public static boolean functional(BlockState state) {
        return state.is(Blocks.REDSTONE_WIRE) || state.is(Blocks.MELON_STEM)
            || state.is(Blocks.PUMPKIN_STEM) || state.is(Blocks.ATTACHED_MELON_STEM)
            || state.is(Blocks.ATTACHED_PUMPKIN_STEM);
    }
}
