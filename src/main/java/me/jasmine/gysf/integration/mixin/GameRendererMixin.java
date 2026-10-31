package me.jasmine.gysf.integration.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.llamalad7.mixinextras.sugar.Local;
import me.jasmine.gysf.debug.WorldDepthDebug;
import me.jasmine.gysf.debug.TerrainSurfaceDebug;
import me.jasmine.gysf.integration.minecraft.SurfaceDebugAccess;
import me.jasmine.gysf.integration.minecraft.GpuDiagnostics;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin implements SurfaceDebugAccess {
    @Shadow @Final private RenderTarget mainRenderTarget;
    @Unique private boolean gysf$frameReported;
    @Unique private boolean gysf$worldReported;
    @Unique private final WorldDepthDebug gysf$depthDebug = new WorldDepthDebug();
    @Unique private final TerrainSurfaceDebug gysf$surfaceDebug = new TerrainSurfaceDebug();

    @Override
    public TerrainSurfaceDebug gysf$surfaceDebug() {
        return gysf$surfaceDebug;
    }

    @Inject(method = "render()V", at = @At("RETURN"))
    private void gysf$observeFrame(CallbackInfo ci) {
        gysf$depthDebug.frame();
        gysf$surfaceDebug.frame();
        if (GpuDiagnostics.ENABLED && !gysf$frameReported) {
            GpuDiagnostics.reportTarget("frame-end (depth is no longer world depth)", mainRenderTarget);
            gysf$frameReported = true;
        }
    }

    @Inject(
        method = "renderLevel()V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V")
    )
    private void gysf$observeWorldBeforeHand(CallbackInfo ci, @Local Matrix4f projectionMatrix) {
        if (GpuDiagnostics.ENABLED && !gysf$worldReported) {
            GpuDiagnostics.reportTarget("world-before-hand", mainRenderTarget);
            gysf$worldReported = true;
        }
        gysf$depthDebug.render(mainRenderTarget, projectionMatrix);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void gysf$closeDepthDebug(CallbackInfo ci) {
        gysf$depthDebug.close();
        gysf$surfaceDebug.close();
    }
}
