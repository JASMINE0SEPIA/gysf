package me.jasmine.gysf.material;

import com.mojang.blaze3d.vertex.QuadInstance;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** One reusable adapter per section build. RGB means material tint, never host lighting. */
public final class TerrainMaterialOutput implements BlockQuadOutput {
    private final QuadInstance material = new QuadInstance();
    private BlockQuadOutput output;
    private BlockState state;
    private BlockAndTintGetter level;
    private BlockPos pos;
    private BlockColors colors;
    private int cachedIndex;
    private int cachedColor;

    public void begin(BlockQuadOutput output, BlockState state, BlockAndTintGetter level, BlockPos pos, BlockColors colors) {
        this.output = output;
        this.state = state;
        this.level = level;
        this.pos = pos;
        this.colors = colors;
        cachedIndex = Integer.MIN_VALUE;
    }

    public void end() {
        output = null;
        state = null;
        level = null;
        pos = null;
        colors = null;
    }

    @Override
    public void put(float x, float y, float z, BakedQuad quad, QuadInstance shaded) {
        int index = quad.materialInfo().tintIndex();
        if (cachedIndex != index) {
            cachedIndex = index;
            cachedColor = -1;
            // Vanilla's state-dependent colors. Decorative biome and fixed vegetation tints are neutral.
            boolean functional = state.is(Blocks.REDSTONE_WIRE) || state.is(Blocks.MELON_STEM)
                || state.is(Blocks.PUMPKIN_STEM) || state.is(Blocks.ATTACHED_MELON_STEM)
                || state.is(Blocks.ATTACHED_PUMPKIN_STEM);
            if (index >= 0 && functional) {
                var source = colors.getTintSource(state, index);
                if (source != null) cachedColor = source.colorInWorld(state, level, pos);
            }
        }
        material.setColor(cachedColor);
        for (int vertex = 0; vertex < 4; vertex++) material.setLightCoords(vertex, shaded.getLightCoords(vertex));
        material.setOverlayCoords(shaded.overlayCoords());
        output.put(x, y, z, quad, material);
    }
}
