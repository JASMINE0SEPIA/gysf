package dev.comfyfluffy.caustica.compat.sodium;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.client.RtVideoOptions;
import dev.comfyfluffy.caustica.client.gui.RtSharcOptionsScreen;
import dev.comfyfluffy.caustica.client.gui.RtSubScreens;
import dev.comfyfluffy.caustica.client.gui.RtVideoOptionsScreen;
import java.util.function.Function;
import java.util.function.Supplier;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionPageBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Loaded exclusively by Sodium; the normal client never links Sodium classes. */
public final class GysfSodiumOptions implements ConfigEntryPoint {
    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        if (!CausticaConfig.Rt.ENABLED.value()) return;
        var mod = builder.registerOwnModOptions();
        mod.setColorTheme(builder.createColorTheme().setBaseThemeRGB(0xB8B8B8));
        mod.setNonTintedIcon(Identifier.parse("caustica:icon-gysf.png"));

        // Backend-dependent controls retain their capability checks and live page refreshes.
        var image = page(builder, "imageMenuHeader");
        var backends = builder.createOptionGroup();
        link(builder, backends, "quality", "caustica.options.rt.qualityHeader", RtSubScreens::quality);
        link(builder, backends, "upscaling", "caustica.options.rt.upscalingHeader", RtSubScreens::upscaling);
        link(builder, backends, "frame_gen", "caustica.options.rt.frameGenHeader", RtSubScreens::frameGen);
        link(builder, backends, "sharc", "caustica.options.sharc.settingsButton",
                parent -> new RtSharcOptionsScreen(parent, Minecraft.getInstance().options));
        image.addOptionGroup(backends);
        group(builder, image, "lightsHeader", RtVideoOptions::lightOptions);
        mod.addPage(image);

        var display = page(builder, "displayMenuHeader");
        group(builder, display, "exposureHeader", RtVideoOptions::exposureOptions);
        group(builder, display, "tonemapHeader", RtVideoOptions::tonemapOptions);
        group(builder, display, "hdrHeader", RtVideoOptions::hdrOptions);
        mod.addPage(display);

        var world = page(builder, "worldMenuHeader");
        group(builder, world, "skyHeader", RtVideoOptions::skyOptions);
        group(builder, world, "worldHeader", RtVideoOptions::worldOptions);
        group(builder, world, "effectsHeader", RtVideoOptions::effectsOptions);
        group(builder, world, "pomHeader", RtVideoOptions::pomOptions);
        mod.addPage(world);

        var water = page(builder, "waterHeader");
        group(builder, water, "waterHeader", RtVideoOptions::waterOptions);
        group(builder, water, "fogHeader", RtVideoOptions::fogOptions);
        var clouds = builder.createOptionGroup();
        link(builder, clouds, "clouds", "caustica.options.rt.cloudsHeader", RtSubScreens::clouds);
        water.addOptionGroup(clouds);
        mod.addPage(water);

        var advanced = page(builder, "advancedMenuHeader");
        group(builder, advanced, "streamingHeader", RtVideoOptions::streamingOptions);
        group(builder, advanced, "debugHeader", RtVideoOptions::debugOptions);
        var all = builder.createOptionGroup();
        // Includes per-page/global reset and optional LOD refresh actions.
        link(builder, all, "all_settings", "caustica.options.rt.settingsButton",
                parent -> new RtVideoOptionsScreen(parent, Minecraft.getInstance().options));
        advanced.addOptionGroup(all);
        mod.addPage(advanced);
    }

    private static OptionPageBuilder page(ConfigBuilder builder, String key) {
        return builder.createOptionPage().setName(Component.translatable("caustica.options.rt." + key));
    }

    private static void group(ConfigBuilder builder, OptionPageBuilder page, String key,
            Supplier<OptionInstance<?>[]> factory) {
        var group = builder.createOptionGroup().setName(Component.translatable("caustica.options.rt." + key));
        var initial = factory.get();
        var defaults = RtVideoOptions.defaultValues(factory);
        for (int index = 0; index < initial.length; index++) {
            int optionIndex = index;
            group.addOption(SodiumOptionAdapter.create(builder, () -> factory.get()[optionIndex], defaults[index].get()));
        }
        page.addOptionGroup(group);
    }

    private static void link(ConfigBuilder builder, OptionGroupBuilder group, String id, String key,
            Function<Screen, Screen> screen) {
        group.addOption(builder.createExternalButtonOption(Identifier.fromNamespaceAndPath("gysf", id))
                .setName(Component.translatable(key))
                .setTooltip(Component.translatable("caustica.options.rt.menuButton", Component.translatable(key)))
                .setScreenConsumer(parent -> Minecraft.getInstance().gui.setScreen(
                        screen.apply(new SodiumSettingsReturnScreen(parent)))));
    }
}
