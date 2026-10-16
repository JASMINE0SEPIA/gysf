package dev.comfyfluffy.caustica.smoke.mixin;

import dev.comfyfluffy.caustica.rt.terrain.RtWaterGeometry;
import dev.comfyfluffy.caustica.smoke.WaterBoundsComparison;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RtWaterGeometry.class)
abstract class WaterBoundsPackMixin {
    @Inject(method="pack",at=@At("RETURN"))
    private static void gysf$capture(float[] positions,int[] indices,int first,int count,float[] shore,
                                   CallbackInfoReturnable<RtWaterGeometry.Packed> ci) {
        WaterBoundsComparison.packed(ci.getReturnValue());
    }
}
