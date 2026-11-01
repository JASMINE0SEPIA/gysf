package me.jasmine.gysf.integration.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.frontend.shaders.GlslCompiler;
import com.mojang.renderpearl.frontend.shaders.PipelineBuilder;
import com.mojang.renderpearl.util.ShaderCompileException;
import me.jasmine.gysf.gpu.SlangShaderSource;
import me.jasmine.gysf.render.terrain.TransparentTerrainShaders;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PipelineBuilder.class)
public abstract class PipelineBuilderMixin {
    @WrapOperation(
        method = "generateBackendCreateInfo",
        at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/frontend/shaders/GlslCompiler;compileToSpv(Ljava/lang/String;Ljava/lang/String;Lcom/mojang/renderpearl/api/pipeline/ShaderType;Lnet/minecraft/client/renderer/ShaderDefines;Lcom/mojang/renderpearl/api/pipeline/ShaderSource;)Lcom/mojang/renderpearl/backend/api/SpvModule;")
    )
    private SpvModule gysf$loadSlangBinary(
        GlslCompiler compiler, String name, String text, ShaderType type, ShaderDefines defines,
        ShaderSource source, Operation<SpvModule> original,
        @Local(argsOnly = true) RenderPipeline pipeline
    ) throws ShaderCompileException {
        if (source instanceof SlangShaderSource slang) {
            return slang.loadModule(Identifier.parse(name), type);
        }
        if (TransparentTerrainShaders.handles(pipeline)) {
            return original.call(compiler, "gysf:core/terrain_translucent", TransparentTerrainShaders.load(type), type, defines, source);
        }
        return original.call(compiler, name, text, type, defines, source);
    }
}
