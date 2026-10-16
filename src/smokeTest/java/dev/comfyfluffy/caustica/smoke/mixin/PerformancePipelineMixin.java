package dev.comfyfluffy.caustica.smoke.mixin;

import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import dev.comfyfluffy.caustica.rt.pipeline.RtPipeline;
import dev.comfyfluffy.caustica.smoke.WaterShaderComparison;
import java.io.InputStream;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(RtPipeline.class)
abstract class PerformancePipelineMixin {
    @Redirect(method="loadModule",at=@At(value="INVOKE",target="Ljava/lang/Class;getResourceAsStream(Ljava/lang/String;)Ljava/io/InputStream;"))
    private static InputStream gysf$loadWaterVariant(Class<?> owner,String name) {
        return WaterShaderComparison.shaderStream(owner,name);
    }
    @Redirect(method="trace(Lorg/lwjgl/vulkan/VkCommandBuffer;IILjava/nio/ByteBuffer;I)V",
            at=@At(value="FIELD",target="Ldev/comfyfluffy/caustica/rt/pipeline/RtPipeline;pipeline:J"))
    private long gysf$selectPipeline(RtPipeline owner) {
        return WaterShaderComparison.pipeline(owner,((PerformancePipelineAccessor)(Object)owner).gysf$pipelineHandle());
    }
    @Redirect(method="trace(Lorg/lwjgl/vulkan/VkCommandBuffer;IILjava/nio/ByteBuffer;I)V",
            at=@At(value="FIELD",target="Ldev/comfyfluffy/caustica/rt/pipeline/RtPipeline;sbt:Ldev/comfyfluffy/caustica/rt/accel/RtBuffer;"))
    private RtBuffer gysf$selectSbt(RtPipeline owner) {
        return WaterShaderComparison.sbt(owner,((PerformancePipelineAccessor)(Object)owner).gysf$sbtBuffer());
    }
}
