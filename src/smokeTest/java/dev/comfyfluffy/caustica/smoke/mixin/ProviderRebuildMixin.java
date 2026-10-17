package dev.comfyfluffy.caustica.smoke.mixin;

import dev.comfyfluffy.caustica.smoke.ProviderRebuildCapture;
import dev.comfyfluffy.caustica.rt.terrain.RtDistantHorizonsTerrain;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class ProviderRebuildMixin {
    @Inject(method="render",at=@At("HEAD"))
    private void gysf$rebuildAfterClientTick(CallbackInfo ci){ProviderRebuildCapture.beforeRender();}
}

@Mixin(RtDistantHorizonsTerrain.class)
abstract class ProviderBoundaryMixin {
    @Inject(method="frame",at=@At("TAIL"))
    private void gysf$checkProviderBeforeTrace(CallbackInfo ci){ProviderRebuildCapture.checkBoundary();}
}
