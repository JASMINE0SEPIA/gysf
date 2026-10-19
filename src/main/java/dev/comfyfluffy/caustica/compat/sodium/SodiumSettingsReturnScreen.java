package dev.comfyfluffy.caustica.compat.sodium;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.caffeinemc.mods.sodium.client.config.ConfigManager;
import net.caffeinemc.mods.sodium.client.config.structure.OptionPage;
import net.caffeinemc.mods.sodium.client.config.structure.StatefulOption;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Reloads externally changed settings without losing unapplied Sodium edits. */
final class SodiumSettingsReturnScreen extends Screen {
    private final Screen parent;

    SodiumSettingsReturnScreen(Screen parent) {
        super(Component.empty());
        this.parent = parent;
    }

    @Override
    protected void init() {
        var config = ConfigManager.CONFIG;
        List<Runnable> pending = new ArrayList<>();
        for (var mod : config.getModOptions()) {
            for (var page : mod.pages()) {
                if (!(page instanceof OptionPage options)) continue;
                for (var group : options.groups()) {
                    for (var option : group.options()) {
                        if (option instanceof StatefulOption<?> state && state.hasChanged())
                            preservePending(state, pending);
                    }
                }
            }
        }
        config.resetAllOptionsFromBindings();
        pending.forEach(Runnable::run);
        this.minecraft.gui.setScreen(parent);
    }

    private static <T> void preservePending(StatefulOption<T> option, List<Runnable> pending) {
        // A reset/change made on the external page takes precedence over an older pending edit.
        if (Objects.equals(option.getBinding().load(), option.getAppliedValue())) {
            T value = option.getValidatedValue();
            pending.add(() -> option.modifyValue(value));
        }
    }
}
