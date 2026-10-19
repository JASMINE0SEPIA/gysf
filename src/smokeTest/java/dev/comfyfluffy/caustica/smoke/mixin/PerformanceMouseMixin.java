package dev.comfyfluffy.caustica.smoke.mixin;

import dev.comfyfluffy.caustica.smoke.PerformanceCapture;
import dev.comfyfluffy.caustica.smoke.LodReentryCapture;
import dev.comfyfluffy.caustica.smoke.WeatherCapture;
import dev.comfyfluffy.caustica.smoke.WaterCameraCapture;
import dev.comfyfluffy.caustica.smoke.WaterGuideCapture;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
abstract class PerformanceMouseMixin {
    @Inject(method="turnPlayer",at=@At("HEAD"),cancellable=true)
    private void gysf$ignoreBenchmarkMouse(double elapsed,CallbackInfo ci) {if(PerformanceCapture.locked() || WaterCameraCapture.locked() || WaterGuideCapture.locked() || LodReentryCapture.locked() || WeatherCapture.locked())ci.cancel();}
}

