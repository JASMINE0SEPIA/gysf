package me.jasmine.gysf.integration.mixin;

import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.util.ARGB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Keep fluid geometry, UV animation and alpha; discard biome tint and cardinal shading. */
@Mixin(FluidRenderer.class)
public abstract class FluidMaterialMixin {
    @ModifyVariable(method = "vertex", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int gysf$neutralFluidColor(int color) {
        return ARGB.color(ARGB.alpha(color), 255, 255, 255);
    }
}
