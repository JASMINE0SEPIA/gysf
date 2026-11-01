package me.jasmine.gysf.integration.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.jasmine.gysf.material.MaterialTintRules;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.model.BlockStateModelWrapper;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BlockStateModelWrapper.class)
public abstract class BlockDisplayMaterialMixin {
    @WrapOperation(method = "updateTints", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/color/block/BlockTintSource;color(Lnet/minecraft/world/level/block/state/BlockState;)I"))
    private int gysf$materialTint(BlockTintSource source, BlockState state, Operation<Integer> original) {
        return MaterialTintRules.functional(state) ? original.call(source, state) : -1;
    }
}
