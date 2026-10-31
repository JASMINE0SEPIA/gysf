package me.jasmine.gysf.integration.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import me.jasmine.gysf.integration.minecraft.SurfaceDebugAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin({ChunkSectionsToRender.DrawIndirect.class, ChunkSectionsToRender.DrawSeparate.class})
public abstract class TerrainDrawMixin {
    @WrapOperation(
        method = "render",
        at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/commands/RenderPass;setPipeline(Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;)V")
    )
    private void gysf$bindSurfacePreview(
        RenderPass pass, CompiledRenderPipeline pipeline, Operation<Void> original,
        @Local(argsOnly = true) ChunkSectionLayer layer
    ) {
        var debug = ((SurfaceDebugAccess) Minecraft.getInstance().gameRenderer).gysf$surfaceDebug();
        if (!debug.bind(pass, layer, (Object) this instanceof ChunkSectionsToRender.DrawIndirect)) {
            original.call(pass, pipeline);
        }
    }
}
