package dev.comfyfluffy.caustica.smoke;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.client.RtVideoOptions;
import dev.comfyfluffy.caustica.client.gui.RtSettingsSubScreen;
import net.caffeinemc.mods.sodium.client.config.ConfigManager;
import net.caffeinemc.mods.sodium.client.config.structure.ExternalButtonOption;
import net.caffeinemc.mods.sodium.client.config.structure.OptionPage;
import net.caffeinemc.mods.sodium.client.config.structure.StatefulOption;
import net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import static dev.comfyfluffy.caustica.smoke.SettingsCapture.require;

/** Assertions exercise real Sodium option state, storage hooks and live vanilla callbacks. */
final class SodiumSettingsCapture {
    private Screen root, parent;
    private OptionPage waterPage;
    private boolean initialWaves;
    private int initialStrength;
    private String initialStyle;

    void open(Minecraft client, Screen parent) {
        this.parent = parent;
        var mod = ConfigManager.CONFIG.getModOptions().stream().filter(m -> m.configId().equals("gysf"))
                .findFirst().orElseThrow(() -> new IllegalStateException("gysf Sodium entrypoint not registered"));
        require(mod.pages().size() == 5, "Expected five gysf pages");
        require(Identifier.parse("caustica:icon-gysf.png").equals(mod.icon()) && !mod.iconMonochrome(), "Missing grayscale icon");
        waterPage = (OptionPage) mod.pages().get(3);
        int count = mod.pages().stream().map(p -> (OptionPage) p).flatMap(p -> p.groups().stream())
                .mapToInt(group -> group.options().size()).sum();
        require(count >= 65, "Missing RT options: " + count);
        root = VideoSettingsScreen.createScreen(parent, waterPage);
        client.gui.setScreen(root);
        initialWaves = CausticaConfig.Rt.Composite.WATER_WAVES.value();
        initialStrength = integer("waterWaveStrength").getAppliedValue();
        initialStyle = CausticaConfig.Rt.Composite.CLOUD_STYLE.get();
        System.out.println("GYSF_SODIUM_SETTINGS: registered pages=5 options=" + count);
    }

    void transactions(Minecraft client) {
        var config = ConfigManager.CONFIG;
        var waves = bool("waterWaves");
        var strength = integer("waterWaveStrength");
        waves.modifyValue(!initialWaves);
        require(CausticaConfig.Rt.Composite.WATER_WAVES.value() == initialWaves, "Pending edit applied early");
        config.resetAllOptionsFromBindings();
        require(waves.getValidatedValue() == initialWaves && !waves.hasChanged(), "Undo failed");
        waves.modifyValue(!initialWaves);
        strength.modifyValue(23);
        config.applyAllOptions();
        require(CausticaConfig.Rt.Composite.WATER_WAVES.value() != initialWaves, "Boolean apply failed");
        require(Math.abs(CausticaConfig.Rt.Composite.WATER_WAVE_STRENGTH.value() - 2.3f) < 0.0001f,
                "Tenths conversion failed");
        try (var file = com.electronwill.nightconfig.core.file.CommentedFileConfig.of("config/caustica.toml")) {
            file.load();
            Number saved = file.get(CausticaConfig.Rt.Composite.WATER_WAVE_STRENGTH.tomlPath());
            require(saved != null && Math.abs(saved.floatValue() - 2.3f) < 0.0001f, "Apply not saved to TOML");
        }
        root = VideoSettingsScreen.createScreen(parent, waterPage);
        client.gui.setScreen(root);
        require(strength.getAppliedValue() == 23 && waves.getAppliedValue() != initialWaves, "Reopen lost applied values");
        // UI defaults must be built without resetting or transforming live config.
        var defaults = RtVideoOptions.defaultValues(RtVideoOptions::waterOptions);
        require(defaults[2].get().equals(Math.round(CausticaConfig.Rt.Composite.WATER_WAVE_STRENGTH.defaultValue() * 10)),
                "Factory defaults use current values");
        require(strength.getAppliedValue() == 23 && Math.abs(CausticaConfig.Rt.Composite.WATER_WAVE_STRENGTH.value() - 2.3f) < 0.0001f,
                "Default preview mutated live config");
        strength.resetToDefault();
        config.applyAllOptions();
        require(Math.abs(CausticaConfig.Rt.Composite.WATER_WAVE_STRENGTH.value()
                - CausticaConfig.Rt.Composite.WATER_WAVE_STRENGTH.defaultValue()) < 0.0001f, "Sodium factory reset failed");
        var sun = integer("sunSize");
        int oldSun = sun.getAppliedValue();
        sun.modifyValue(15); config.applyAllOptions();
        require(Math.abs(Math.toDegrees(CausticaConfig.Rt.Composite.SUN_ANGULAR_RADIUS.value()) - 1.5) < 0.001,
                "Degree/radian conversion failed");
        sun.modifyValue(oldSun); config.applyAllOptions();
        checkDebugChoices();
        System.out.println("GYSF_SODIUM_SETTINGS: apply undo save reopen defaults units PASS");
    }

    void subScreenRoundTrip(Minecraft client) {
        var strength = integer("waterWaveStrength");
        strength.modifyValue(17);
        var button = (ExternalButtonOption) ConfigManager.CONFIG.getOption(Identifier.parse("gysf:clouds"));
        button.getCurrentScreenConsumer().accept(root);
        require(client.gui.screen() instanceof RtSettingsSubScreen, "Cloud sub-screen not opened");
        String label = Component.translatable("caustica.options.rt.resetToDefaults").getString();
        require(SettingsCapture.click(client.gui.screen(), label), "Cloud reset button missing");
        client.gui.screen().onClose();
        require(client.gui.screen() == root, "Sub-screen did not return to Sodium");
        require(strength.hasChanged() && strength.getValidatedValue() == 17, "Sub-screen discarded pending native edit");
        // Reset native water values through the complete RT hub and ensure returned bindings refresh.
        var all = (ExternalButtonOption) ConfigManager.CONFIG.getOption(Identifier.parse("gysf:all_settings"));
        CausticaConfig.Rt.Composite.FOG_BIOME_TINT.set(0.63f);
        all.getCurrentScreenConsumer().accept(root);
        require(SettingsCapture.click(client.gui.screen(), Component.translatable("caustica.options.rt.resetAll").getString()),
                "Global reset button missing");
        client.gui.screen().onClose();
        require(client.gui.screen() == root, "Hub did not return to Sodium");
        require(CausticaConfig.Rt.Composite.FOG_BIOME_TINT.value()
                == CausticaConfig.Rt.Composite.FOG_BIOME_TINT.defaultValue(), "Global reset missed fog settings");
        require(bool("waterWaves").getAppliedValue() == CausticaConfig.Rt.Composite.WATER_WAVES.value(),
                "Returned Sodium binding is stale after reset");
        ConfigManager.CONFIG.resetAllOptionsFromBindings();
        bool("waterWaves").modifyValue(initialWaves);
        strength.modifyValue(initialStrength);
        ConfigManager.CONFIG.applyAllOptions();
        CausticaConfig.Rt.Composite.CLOUD_STYLE.set(initialStyle);
        CausticaConfig.save();
        System.out.println("GYSF_SODIUM_SETTINGS: sub-screen reset return pending edits PASS");
    }

    void finish(Minecraft client) {
        root.onClose();
        require(client.gui.screen() == parent, "Sodium Done returned to wrong parent");
        System.out.println("GYSF_SODIUM_SETTINGS: PASS");
    }

    @SuppressWarnings("unchecked")
    private static <T> StatefulOption<T> option(String key) {
        return (StatefulOption<T>) ConfigManager.CONFIG.getOption(Identifier.parse("gysf:caustica.options.rt." + key.toLowerCase(java.util.Locale.ROOT)));
    }
    private static StatefulOption<Integer> integer(String key) { return option(key); }
    private static StatefulOption<Boolean> bool(String key) { return option(key); }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void checkDebugChoices() {
        StatefulOption debug = option("debugView");
        Object previous = debug.getAppliedValue();
        Object slot = previous.getClass().getEnumConstants()[8];
        debug.modifyValue(slot);
        ConfigManager.CONFIG.applyAllOptions();
        require(CausticaConfig.Rt.Composite.DEBUG_VIEW.value() == 10, "Non-contiguous enum mapping failed");
        debug.modifyValue(previous);
        ConfigManager.CONFIG.applyAllOptions();
    }
}
