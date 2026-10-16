package dev.comfyfluffy.caustica.smoke.mixin;

import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.smoke.WaterShaderComparison;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RtComposite.class)
abstract class PerformancePipelineShutdownMixin {
    @Inject(method="destroy",at=@At("HEAD"))
    private void gysf$releaseComparison(CallbackInfo ci){WaterShaderComparison.destroy();}
}
