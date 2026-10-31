package me.jasmine.gysf.debug;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import me.jasmine.gysf.gpu.SlangShaderSource;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Replaces only the shader on the host's opaque/cutout terrain draws. Owns no meshes or targets. */
public final class TerrainSurfaceDebug implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("gysf");
    private static final String[] MODES = {"off", "raw atlas color", "geometry normals", "grid 16", "grid 8", "host vertex color (tint and shading)"};
    private static final Map<String, String> HOST_BINDINGS = Map.of(
        "GlobalsData_std140", "Globals", "ProjectionData_std140", "Projection",
        "TerrainData_std140", "TerrainUniform", "SectionData_std140", "ChunkSection"
    );
    private final CompiledRenderPipeline[] pipelines = new CompiledRenderPipeline[4];
    private List<CompletableFuture<CompiledRenderPipeline.Pending>> compiling;
    private int mode;

    public void cycle() {
        mode = (mode + 1) % MODES.length;
        LOGGER.info("Terrain surface debug {} (F7)", MODES[mode]);
    }

    public void disable() {
        mode = 0;
    }

    public boolean ready() {
        return compiling == null && pipelines[0] != null;
    }

    public void frame() {
        RenderSystem.assertOnRenderThread();
        if (compiling != null && compiling.stream().allMatch(CompletableFuture::isDone)) {
            finishCompilation();
        }
        if (mode == 0) {
            releasePipelines();
        } else if (compiling == null && pipelines[0] == null) {
            compiling = new ArrayList<>(4);
            for (int index = 0; index < 4; index++) {
                compiling.add(RenderSystem.getDevice().compilePipeline(description(index), new SlangShaderSource(HOST_BINDINGS), Util.backgroundExecutor()));
            }
        }
    }

    public boolean bind(RenderPass pass, ChunkSectionLayer layer, boolean multidraw) {
        if (mode == 0 || !ready() || layer.translucent()) {
            return false;
        }
        int index = (multidraw ? 2 : 0) + (layer == ChunkSectionLayer.CUTOUT ? 1 : 0);
        pass.setPipeline(pipelines[index]);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var parameters = stack.calloc(16);
            parameters.putInt(0, mode).putFloat(4, layer == ChunkSectionLayer.CUTOUT ? 0.5f : -1.0f);
            pass.pushConstants(parameters);
        }
        return true;
    }

    private static RenderPipeline description(int index) {
        boolean multidraw = index >= 2;
        boolean cutout = (index & 1) != 0;
        var builder = RenderPipeline.builder()
            .withLocation(Identifier.parse("gysf:debug/surface_" + index))
            .withVertexShader(Identifier.parse("gysf:debug/surface_" + (multidraw ? "multi" : "single")))
            .withFragmentShader(Identifier.parse("gysf:debug/terrain_surface"))
            .withBindGroupLayout(BindGroupLayouts.GLOBALS)
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.TERRAIN_INFO)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
            .withVertexBinding(0, DefaultVertexFormat.BLOCK)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withDepthStencilState(DepthStencilState.DEFAULT)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withCull(layerFor(cutout).pipeline(multidraw).isCull())
            .withPushConstantSize(16);
        if (multidraw) {
            builder.withVertexBinding(1, DefaultVertexFormat.CHUNK_DATA_INSTANCED);
        } else {
            builder.withBindGroupLayout(BindGroupLayouts.CHUNK_SECTION);
        }
        return builder.build();
    }

    private static ChunkSectionLayer layerFor(boolean cutout) {
        return cutout ? ChunkSectionLayer.CUTOUT : ChunkSectionLayer.SOLID;
    }

    private void finishCompilation() {
        var tasks = compiling;
        compiling = null;
        boolean failed = false;
        // Finish every task even if one failed, so other pending modules are not abandoned.
        for (int index = 0; index < tasks.size(); index++) {
            try {
                pipelines[index] = tasks.get(index).join().finishCompile();
                failed |= pipelines[index] == null;
            } catch (CompletionException exception) {
                failed = true;
                LOGGER.error("Terrain surface debug compilation failed", exception);
            }
        }
        if (failed) {
            mode = 0;
            releasePipelines();
            LOGGER.error("Terrain surface debug unavailable; keeping host terrain rendering");
        } else {
            LOGGER.info("Terrain surface debug pipelines ready (single and multidraw, solid and cutout)");
        }
    }

    private void releasePipelines() {
        boolean released = false;
        for (int index = 0; index < pipelines.length; index++) {
            if (pipelines[index] != null) {
                pipelines[index].close();
                pipelines[index] = null;
                released = true;
            }
        }
        if (released) LOGGER.info("Terrain surface debug pipelines released");
    }

    @Override
    public void close() {
        if (compiling != null) finishCompilation();
        releasePipelines();
    }
}
