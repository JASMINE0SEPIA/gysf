package dev.comfyfluffy.caustica.rt;

import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import static org.lwjgl.vulkan.KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR;
import static org.lwjgl.vulkan.KHRSynchronization2.vkCmdPipelineBarrier2KHR;
import static org.lwjgl.vulkan.VK13.*;

/** Optional completed-frame readback for the one-invocation finite-water camera pass. */
public final class RtWaterCameraProbe {
    private RtWaterCameraProbe() {}
    private static final boolean VALIDATE=Boolean.getBoolean("gysf.rt.validateCameraMedium");
    private static long samples,resolved,wetSamples,drySamples,toWater,toAir,disagreements;
    public record Stats(long samples,long resolved,long wet,long dry,long toWater,long toAir,long disagreements) {}
    public static boolean validationEnabled() { return VALIDATE; }
    public static Stats stats() { return new Stats(samples,resolved,wetSamples,drySamples,toWater,toAir,disagreements); }

    static void observe(ByteBuffer data) {
        int state=data.getInt(0),wet=state&1,flags=data.getInt(4);
        if((state&~3)!=0 || (flags&~63)!=0)throw new IllegalStateException("Invalid GPU camera-medium record");
        samples++;
        if((flags&3)!=0) {
            resolved++;
            if(wet!=0)wetSamples++;else drySamples++;
            boolean fallback=(flags&16)!=0;
            if(wet!=0 && !fallback)toWater++;
            if(wet==0 && fallback)toAir++;
        }
        if((flags&32)!=0)disagreements++;
    }

    static void barrier(VkCommandBuffer cmd,MemoryStack stack) {
        var memory=VkMemoryBarrier2.calloc(1,stack);
        memory.get(0).sType$Default()
                .srcStageMask(VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR)
                .srcAccessMask(VK_ACCESS_2_SHADER_WRITE_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR | (VALIDATE?VK_PIPELINE_STAGE_2_HOST_BIT:0))
                .dstAccessMask(VK_ACCESS_2_SHADER_READ_BIT | (VALIDATE?VK_ACCESS_2_HOST_READ_BIT:0));
        var dependency=VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(memory);
        vkCmdPipelineBarrier2KHR(cmd,dependency);
    }
}
