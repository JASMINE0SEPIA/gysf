package dev.comfyfluffy.caustica.smoke.mixin;

import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import dev.comfyfluffy.caustica.rt.pipeline.RtPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RtPipeline.class)
public interface PerformancePipelineAccessor {
    @Accessor("pipeline") long gysf$pipelineHandle();
    @Accessor("sbt") RtBuffer gysf$sbtBuffer();
}
