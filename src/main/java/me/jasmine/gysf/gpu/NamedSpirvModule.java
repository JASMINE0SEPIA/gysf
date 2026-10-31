package me.jasmine.gysf.gpu;

import com.mojang.renderpearl.frontend.shaders.SPIRVModule;
import com.mojang.renderpearl.util.ShaderCompileException;
import com.mojang.renderpearl.api.pipeline.ShaderType;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

/** Maps Slang block type names to host binding names, without changing SPIR-V or data layout. */
final class NamedSpirvModule extends SPIRVModule {
    private final Map<String, String> aliases;
    private Reflection namedReflection;

    NamedSpirvModule(ByteBuffer code, ShaderType type, Map<String, String> aliases) {
        super(code, type);
        this.aliases = aliases;
    }

    @Override
    public Reflection reflect() throws ShaderCompileException {
        if (namedReflection == null) namedReflection = new NamedReflection(super.reflect(), aliases);
        return namedReflection;
    }

    @Override
    public Reflection getReflectionInfoIfAvailable() {
        return namedReflection;
    }

    private record NamedReflection(Reflection original, List<Descriptor> descriptors) implements Reflection {
        NamedReflection(Reflection original, Map<String, String> aliases) {
            this(original, original.descriptors().stream().map(descriptor ->
                (Descriptor) new NamedDescriptor(descriptor, aliases.getOrDefault(descriptor.name(), descriptor.name()))).toList());
        }

        @Override public List<InterfaceVariable> inputs() { return original.inputs(); }
        @Override public List<InterfaceVariable> outputs() { return original.outputs(); }
        @Override public List<PushConstant> pushConstants() { return original.pushConstants(); }
        @Override public List<Descriptor> descriptors(int type) {
            return descriptors.stream().filter(descriptor -> descriptor.resourceType() == type).toList();
        }
    }

    private record NamedDescriptor(Reflection.Descriptor original, String name) implements Reflection.Descriptor {
        @Override public Reflection.Type type() { return original.type(); }
        @Override public int resourceType() { return original.resourceType(); }
        @Override public int descriptorSetIndex() { return original.descriptorSetIndex(); }
        @Override public void descriptorSetIndex(int index) { original.descriptorSetIndex(index); }
        @Override public int binding() { return original.binding(); }
        @Override public void binding(int location) { original.binding(location); }
    }
}
