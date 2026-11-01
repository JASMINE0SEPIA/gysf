package me.jasmine.gysf.integration.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import me.jasmine.gysf.material.TerrainMaterialOutput;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.feature.MovingBlockFeatureRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MovingBlockFeatureRenderer.class)
public abstract class MovingBlockMaterialMixin {

    @WrapOperation(method = "buildGroup", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/block/ModelBlockRenderer;tesselateBlock(Lnet/minecraft/client/renderer/block/BlockQuadOutput;FFFLnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;J)V"))
    private void gysf$writeMaterialColor(ModelBlockRenderer renderer, BlockQuadOutput output,
        float x, float y, float z, BlockAndTintGetter level, BlockPos pos, BlockState state,
        BlockStateModel model, long seed, Operation<Void> original,
        @Share("gysfMaterialOutput") LocalRef<TerrainMaterialOutput> shared,
        @Local(argsOnly = true) FeatureFrameContext context) {
        if (shared.get() == null) shared.set(new TerrainMaterialOutput());
        var material = shared.get();
        material.begin(output, state, level, pos, context.blockColors());
        try {
            original.call(renderer, material, x, y, z, level, pos, state, model, seed);
        } finally {
            material.end();
        }
    }
}


