package me.jasmine.gysf.integration.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public abstract class EntityShadowMixin {
    @Inject(method = "extractShadow", at = @At("HEAD"), cancellable = true)
    private void gysf$skipShadow(EntityRenderState state, Minecraft minecraft, Level level, CallbackInfo ci) {
        state.shadowPieces.clear();
        state.shadowRadius = 0;
        ci.cancel();
    }
}
