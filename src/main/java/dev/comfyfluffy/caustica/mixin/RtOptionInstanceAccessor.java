package dev.comfyfluffy.caustica.mixin;

import java.util.function.Function;
import net.minecraft.client.OptionInstance;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Shares vanilla option labels with optional settings integrations. */
@Mixin(OptionInstance.class)
public interface RtOptionInstanceAccessor<T> {
    @Accessor("caption")
    Component caustica$caption();

    @Accessor("toString")
    Function<T, Component> caustica$valueLabel();
}
