package dev.comfyfluffy.caustica.smoke.mixin;

import dev.comfyfluffy.caustica.rt.accel.RtAccel;
import dev.comfyfluffy.caustica.smoke.WaterBoundsComparison;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RtAccel.Instance.class)
abstract class WaterBoundsInstanceMixin {
    @Inject(method="physicalAddress",at=@At("RETURN"),cancellable=true)
    private void gysf$select(int component,CallbackInfoReturnable<Long> ci) {
        if(component==1)ci.setReturnValue(WaterBoundsComparison.address(ci.getReturnValue()));
    }
}
