package dev.comfyfluffy.caustica.smoke.mixin;

import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.smoke.WaterCameraCapture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Reproduce a translated projection independently of the stationary entity/CPU fluid hint. */
@Mixin(value=RtComposite.class,remap=false)
abstract class WaterCameraProjectionMixin {
    @ModifyVariable(method="captureFrame",at=@At("HEAD"),argsOnly=true,ordinal=0)
    private Matrix4f gysf$testProjectionEye(Matrix4f projection) {
        return WaterCameraCapture.locked() && WaterCameraCapture.projectionBobY()!=0
                ? new Matrix4f(projection).translate(0,WaterCameraCapture.projectionBobY(),0) : projection;
    }
}
