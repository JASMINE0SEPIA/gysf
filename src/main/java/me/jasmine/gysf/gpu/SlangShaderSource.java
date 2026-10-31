package me.jasmine.gysf.gpu;

import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.frontend.shaders.SPIRVModule;
import com.mojang.renderpearl.util.ShaderCompileException;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteOrder;
import java.util.Map;

/** Build-time Slang binaries; the host still owns reflection, bindings and pipeline compilation. */
public final class SlangShaderSource implements ShaderSource {
    private final Map<String, String> bindingAliases;

    public SlangShaderSource() {
        this(Map.of());
    }

    public SlangShaderSource(Map<String, String> bindingAliases) {
        this.bindingAliases = Map.copyOf(bindingAliases);
    }

    @Override
    public String getShader(Identifier id, ShaderType type) {
        // PipelineBuilder checks for source text before reaching the binary bridge.
        return "// gysf precompiled SPIR-V";
    }

    public SpvModule loadModule(Identifier id, ShaderType type) throws ShaderCompileException {
        String suffix = switch (type) {
            case VERTEX -> "vert";
            case FRAGMENT -> "frag";
            default -> throw new ShaderCompileException("Unsupported Slang shader stage: " + type);
        };
        String path = "/assets/" + id.getNamespace() + "/shaders/" + id.getPath() + "." + suffix + ".spv";
        try (var stream = SlangShaderSource.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new ShaderCompileException("Missing Slang binary: " + path);
            }
            byte[] bytes = stream.readAllBytes();
            var code = MemoryUtil.memAlloc(bytes.length).order(ByteOrder.nativeOrder());
            code.put(bytes).flip();
            // SPIRVModule owns this allocation. PipelineBuilder releases it after pipeline compilation.
            return bindingAliases.isEmpty() ? new SPIRVModule(code, type) : new NamedSpirvModule(code, type, bindingAliases);
        } catch (IOException exception) {
            throw new ShaderCompileException("Cannot read Slang binary " + path + ": " + exception.getMessage());
        }
    }

    @Override
    public CachedIncludeSource getInclude(Identifier id) {
        return null;
    }

    @Override
    public void close() {}
}
