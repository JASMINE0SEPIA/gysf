package me.jasmine.gysf.debug;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import me.jasmine.gysf.gpu.SlangShaderSource;
import net.minecraft.util.Util;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** A disposable debug pass, owned by GameRenderer. It never owns or retains the main target. */
public final class WorldDepthDebug implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("gysf");
    private static final Identifier SHADER = Identifier.parse("gysf:debug/world_depth");
    private static final RenderPipeline DESCRIPTION = RenderPipeline.builder()
        .withLocation(SHADER)
        .withVertexShader(SHADER)
        .withFragmentShader(SHADER)
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .withCull(false)
        .withColorTargetState(ColorTargetState.DEFAULT)
        .withBindGroupLayout(BindGroupLayout.builder().withUniform("WorldDepth", UniformType.COMBINED_IMAGE_SAMPLER).build())
        .withPushConstantSize(80)
        .build();
    private static volatile boolean requested;

    private final Matrix4f inverseProjection = new Matrix4f();
    private CompletableFuture<CompiledRenderPipeline.Pending> compiling;
    private CompiledRenderPipeline pipeline;

    public static void toggle() {
        requested = !requested;
        LOGGER.info("World depth debug {} (F8)", requested ? "on" : "off");
    }

    /** Poll without blocking normal frames, including frames in menus after leaving a world. */
    public void frame() {
        RenderSystem.assertOnRenderThread();
        if (compiling != null && compiling.isDone()) {
            finishCompilation();
        }
        if (!requested && pipeline != null) {
            pipeline.close();
            pipeline = null;
            LOGGER.info("World depth debug pipeline released");
        }
    }

    public void render(RenderTarget target, Matrix4f worldProjection) {
        if (!requested) {
            return;
        }
        RenderSystem.assertOnRenderThread();
        if (pipeline == null) {
            if (compiling == null) {
                compiling = RenderSystem.getDevice().compilePipeline(DESCRIPTION, new SlangShaderSource(), Util.backgroundExecutor());
            }
            return;
        }
        var color = target.getColorTextureView();
        var depth = target.getDepthTextureView();
        if (color == null || depth == null) {
            return;
        }

        worldProjection.invert(inverseProjection);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var parameters = stack.malloc(80);
            inverseProjection.get(0, parameters);
            parameters.putFloat(64, target.width).putFloat(68, target.height).putFloat(72, 512.0f).putFloat(76, 0.0f);
            // Depth is sampled, never attached or written; the host tracks its barriers and later use.
            try (var pass = RenderSystem.getDevice().createCommandEncoder()
                .createRenderPass(() -> "gysf world depth debug", color, Optional.empty())) {
                pass.setPipeline(pipeline);
                pass.setUniform("WorldDepth", depth, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                pass.pushConstants(parameters);
                pass.draw(3, 1, 0, 0);
            }
        }
    }

    private void finishCompilation() {
        var task = compiling;
        compiling = null;
        try {
            pipeline = task.join().finishCompile();
            if (pipeline == null) {
                requested = false;
                LOGGER.error("World depth debug pipeline unavailable; keeping host rendering");
            } else {
                LOGGER.info("World depth debug Slang pipeline ready");
            }
        } catch (CompletionException exception) {
            requested = false;
            LOGGER.error("World depth debug compilation failed; keeping host rendering", exception);
        }
    }

    @Override
    public void close() {
        // Shutdown must drain an in-flight compile before the host destroys its device.
        if (compiling != null) {
            finishCompilation();
        }
        if (pipeline != null) {
            pipeline.close();
            pipeline = null;
        }
    }
}
