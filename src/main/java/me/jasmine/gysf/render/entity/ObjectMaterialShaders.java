package me.jasmine.gysf.render.entity;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.util.ShaderCompileException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Native object shader families share unlit materials; overlays, glint and OIT remain functional. */
public final class ObjectMaterialShaders {
    private ObjectMaterialShaders() {}

    public static String source(RenderPipeline pipeline, ShaderType type) throws ShaderCompileException {
        var id = pipeline.getShaders().get(type);
        if (!pipeline.getLocation().getNamespace().equals("minecraft") || id == null || !id.getNamespace().equals("minecraft")) return null;
        String family = switch (id.getPath()) {
            case "core/entity" -> "ENTITY";
            case "core/item" -> "ITEM";
            case "core/block" -> "BLOCK";
            default -> null;
        };
        if (family == null) return null;
        String extension = switch (type) {
            case VERTEX -> "vsh";
            case FRAGMENT -> "fsh";
            default -> throw new ShaderCompileException("Unsupported object stage: " + type);
        };
        String path = "/assets/gysf/shaders/core/object_material." + extension;
        try (var stream = ObjectMaterialShaders.class.getResourceAsStream(path)) {
            if (stream == null) throw new ShaderCompileException("Missing shader: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                .replace("#version 330", "#version 330\n#define GYSF_" + family);
        } catch (IOException exception) {
            throw new ShaderCompileException("Cannot read " + path + ": " + exception.getMessage());
        }
    }
}
