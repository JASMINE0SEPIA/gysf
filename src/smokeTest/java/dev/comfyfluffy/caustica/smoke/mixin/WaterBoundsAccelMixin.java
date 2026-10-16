package dev.comfyfluffy.caustica.smoke.mixin;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.accel.*;
import dev.comfyfluffy.caustica.smoke.WaterBoundsComparison;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(RtAccel.class)
abstract class WaterBoundsAccelMixin {
    @Shadow @Final public long deviceAddress;
    @Inject(method="prepareWaterBlas",at=@At("RETURN"))
    private static void gysf$capture(RtContext ctx,RtBuffer bounds,int count,String label,
                                    CallbackInfoReturnable<RtAccel.PreparedBlas> ci) {
        WaterBoundsComparison.captured(ci.getReturnValue(),count);
    }
    @Inject(method="destroy",at=@At("HEAD"))
    private void gysf$retire(CallbackInfo ci){WaterBoundsComparison.retired(deviceAddress);}
}
