package dev.comfyfluffy.caustica.smoke.mixin;

import dev.comfyfluffy.caustica.smoke.PerformanceCapture;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Wall-clock cadence includes GPU waits and presentation; this is not a per-pass GPU timer. */
@Mixin(Minecraft.class)
abstract class PerformanceFrameMixin {
    @Inject(method="runTick",at=@At("HEAD"))
    private void gysf$lockBenchmarkCamera(boolean advanceGameTime,CallbackInfo ci) { PerformanceCapture.enforceCamera(); }
    @Inject(method="handleKeybinds",at=@At("HEAD"),cancellable=true)
    private void gysf$ignoreBenchmarkKeys(CallbackInfo ci) { if(PerformanceCapture.locked())ci.cancel(); }
    @Inject(method="runTick",at=@At("TAIL"))
    private void gysf$sampleFrame(boolean advanceGameTime,CallbackInfo ci) { PerformanceCapture.frame(); }
}
