package dev.comfyfluffy.caustica.compat.sodium;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.mixin.RtOptionInstanceAccessor;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.StatefulOptionBuilder;
import net.minecraft.client.OptionInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;

/** Reuses RT's units, clamping, formatting and rebuild callbacks, with Sodium's apply/undo model. */
final class SodiumOptionAdapter {
    private static final StorageEventHandler SAVE = CausticaConfig::save;

    // Slots preserve the existing order and non-contiguous enum values (e.g. debug views 7 -> 10).
    private enum Choice { A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P }

    private SodiumOptionAdapter() { }

    @SuppressWarnings("unchecked")
    static <T> OptionBuilder create(ConfigBuilder builder, Supplier<OptionInstance<?>> factory, Object defaultValue) {
        Supplier<OptionInstance<T>> live = () -> (OptionInstance<T>) factory.get();
        var option = live.get();
        var labels = (RtOptionInstanceAccessor<T>) (Object) option;
        var caption = labels.caustica$caption();
        String key = ((TranslatableContents) caption.getContents()).getKey();
        var id = Identifier.fromNamespaceAndPath("gysf", key.toLowerCase(Locale.ROOT));
        StatefulOptionBuilder<?> result;
        if (option.get() instanceof Boolean) {
            var bool = builder.createBooleanOption(id)
                    .setBinding(value -> ((OptionInstance<Boolean>) live.get()).set(value),
                            () -> (Boolean) live.get().get())
                    .setDefaultValue((Boolean) defaultValue);
            result = bool;
        } else if (option.values() instanceof OptionInstance.IntRange range) {
            var integer = builder.createIntegerOption(id)
                    .setRange(range.minInclusive(), range.maxInclusive(), 1)
                    .setBinding(value -> ((OptionInstance<Integer>) live.get()).set(value),
                            () -> (Integer) live.get().get())
                    .setDefaultValue((Integer) defaultValue)
                    .setValueFormatter(value -> valueLabel(option, (T) Integer.valueOf(value)));
            result = integer;
        } else if (option.values() instanceof OptionInstance.Enum<?> values) {
            List<T> choices = (List<T>) values.values();
            if (choices.isEmpty() || choices.size() > Choice.values().length)
                throw new IllegalArgumentException("Unsupported RT choices: " + key);
            var allowed = EnumSet.range(Choice.A, Choice.values()[choices.size() - 1]);
            var enumeration = builder.createEnumOption(id, Choice.class)
                    .setAllowedValues(allowed)
                    .setBinding(value -> live.get().set(choices.get(value.ordinal())),
                            () -> Choice.values()[Math.max(0, choices.indexOf(live.get().get()))])
                    .setDefaultValue(Choice.values()[Math.max(0, choices.indexOf(defaultValue))])
                    .setElementNameProvider(value -> valueLabel(option, choices.get(value.ordinal())));
            result = enumeration;
        } else {
            throw new IllegalArgumentException("Unsupported RT option: " + key);
        }
        // Always re-read config on load/apply: sub-screens and resets may have changed it since registration.
        return result.setName(caption).setTooltip(Component.translatable(key + ".tooltip"))
                .setStorageHandler(SAVE);
    }

    @SuppressWarnings("unchecked")
    private static <T> Component valueLabel(OptionInstance<T> option, T value) {
        Component label = ((RtOptionInstanceAccessor<T>) (Object) option).caustica$valueLabel().apply(value);
        if (label.getContents() instanceof TranslatableContents translated
                && translated.getKey().equals("options.generic_value") && translated.getArgs().length == 2) {
            Object text = translated.getArgs()[1];
            return text instanceof Component component ? component : Component.literal(String.valueOf(text));
        }
        return label;
    }
}
