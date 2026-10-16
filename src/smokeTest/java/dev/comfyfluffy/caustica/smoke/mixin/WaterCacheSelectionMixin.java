package dev.comfyfluffy.caustica.smoke.mixin;

import dev.comfyfluffy.caustica.rt.RtWaterBoundsCache;
import dev.comfyfluffy.caustica.rt.RtGpuExecutor;
import dev.comfyfluffy.caustica.smoke.WaterBoundsComparison;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RtWaterBoundsCache.class)
abstract class WaterCacheSelectionMixin {
    @Inject(method="resolve",at=@At("RETURN"),cancellable=true)
    private void gysf$select(long original,RtGpuExecutor.GraphicsUse use,CallbackInfoReturnable<Long> ci) {
        ci.setReturnValue(WaterBoundsComparison.cachedAddress(original,ci.getReturnValue()));
    }
}
