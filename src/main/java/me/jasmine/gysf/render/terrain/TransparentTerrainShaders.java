package me.jasmine.gysf.render.terrain;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.util.ShaderCompileException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Replace color evaluation only; host pipeline state and all OIT includes stay host-owned. */
public final class TransparentTerrainShaders {
    private static final Set<String> PIPELINES = Set.of(
        "pipeline/translucent_terrain", "pipeline/translucent_terrain_multidraw",
        "pipeline/oit_depth_bounds_terrain", "pipeline/oit_depth_bounds_terrain_multidraw",
        "pipeline/oit_transmittance_terrain", "pipeline/oit_transmittance_terrain_multidraw",
        "pipeline/oit_accumulate_terrain", "pipeline/oit_accumulate_terrain_multidraw"
    );

    private TransparentTerrainShaders() {}

    public static boolean handles(RenderPipeline pipeline) {
        var id = pipeline.getLocation();
        return id.getNamespace().equals("minecraft") && PIPELINES.contains(id.getPath());
    }

    public static String load(ShaderType type) throws ShaderCompileException {
        String extension = switch (type) {
            case VERTEX -> "vsh";
            case FRAGMENT -> "fsh";
            default -> throw new ShaderCompileException("Unsupported transparent terrain stage: " + type);
        };
        String path = "/assets/gysf/shaders/core/terrain_translucent." + extension;
        try (var stream = TransparentTerrainShaders.class.getResourceAsStream(path)) {
            if (stream == null) throw new ShaderCompileException("Missing shader: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new ShaderCompileException("Cannot read " + path + ": " + exception.getMessage());
        }
    }
}
