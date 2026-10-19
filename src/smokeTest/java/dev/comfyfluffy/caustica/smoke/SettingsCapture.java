package dev.comfyfluffy.caustica.smoke;

import dev.comfyfluffy.caustica.client.gui.RtSettingsSubScreen;
import dev.comfyfluffy.caustica.client.gui.RtVideoOptionsScreen;
import dev.comfyfluffy.caustica.rt.RtComposite;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;

/** UI and render replay in a physical copy of the player's world. */
final class SettingsCapture {
    private int ticks, phase, wait, worldTicks;
    private boolean finished;
    private TitleScreen title;
    private SodiumSettingsCapture sodium;

    void tick(Minecraft client) {
        if (++ticks > 6000) throw new IllegalStateException("Settings smoke timed out");
        if (finished) { if (++wait > 40) client.stop(); return; }
        if (phase == 0) {
            if (!(client.gui.screen() instanceof TitleScreen screen) || ticks < 60) return;
            title = screen;
            client.options.pauseOnLostFocus = false;
            client.getWindow().setWindowed(1600, 1000);
            if (FabricLoader.getInstance().isModLoaded("sodium")) {
                sodium = new SodiumSettingsCapture();
                sodium.open(client, title);
            } else {
                var video = new VideoSettingsScreen(title, client, client.options);
                client.gui.setScreen(video);
                require(click(video, Component.translatable("caustica.options.rt.settingsButton").getString()),
                        "Vanilla RT settings button missing");
                require(client.gui.screen() instanceof RtVideoOptionsScreen, "Vanilla RT hub not opened");
                require(click(client.gui.screen(), Component.translatable("caustica.options.rt.menuButton",
                        Component.translatable("caustica.options.rt.waterHeader")).getString()), "Vanilla water link missing");
                require(client.gui.screen() instanceof RtSettingsSubScreen, "Vanilla water page not opened");
            }
            phase = 1; wait = 0;
        } else if (phase == 1 && ++wait == 30) {
            capture(client, sodium == null ? "vanilla-water" : "sodium-water");
        } else if (phase == 1 && wait >= 45) {
            if (sodium != null) sodium.transactions(client);
            phase = 2; wait = 0;
        } else if (phase == 2 && ++wait == 30) {
            capture(client, sodium == null ? "vanilla-return" : "sodium-after-apply");
        } else if (phase == 2 && wait >= 45) {
            if (sodium != null) sodium.subScreenRoundTrip(client);
            phase = 3; wait = 0;
        } else if (phase == 3 && ++wait == 30) {
            if (sodium != null) sodium.finish(client);
            client.gui.setScreen(title);
            Path world = Path.of("saves", "gysf-player-visual").toAbsolutePath().normalize();
            require(world.startsWith(Path.of(".").toAbsolutePath().normalize()) && Files.exists(world.resolve("level.dat")),
                    "Settings smoke requires an isolated copied save");
            client.options.renderDistance().set(6);
            client.options.simulationDistance().set(6);
            client.options.framerateLimit().set(60);
            client.createWorldOpenFlows().openWorld("gysf-player-visual", () -> {
                throw new IllegalStateException("Copied settings test world cancelled");
            });
            phase = 4;
        } else if (phase == 4 && client.level != null && client.player != null) {
            if (RtComposite.INSTANCE.hasFailed()) throw new IllegalStateException("RT failed after settings");
            if (++worldTicks == 600) {
                require(RtComposite.frameCounter() > 120, "Insufficient rendered RT frames");
                capture(client, sodium == null ? "vanilla-world" : "sodium-world");
                System.out.println("GYSF_SMOKE: PASS settings sodium=" + (sodium != null)
                        + " rtFrames=" + RtComposite.frameCounter());
                client.disconnectWithSavingScreen();
                finished = true; wait = 0;
            }
        }
    }

    static boolean click(GuiEventListener element, String caption) {
        if (element instanceof Button button && button.getMessage().getString().equals(caption)) {
            button.onPress(new net.minecraft.client.input.KeyEvent(257, 0, 0)); return true;
        }
        if (element instanceof ContainerEventHandler container) {
            for (var child : container.children()) if (click(child, caption)) return true;
        }
        return false;
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static void capture(Minecraft client, String name) {
        Path folder = Path.of("settings-captures");
        try { Files.createDirectories(folder); } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try { image.writeToFile(folder.resolve(name + ".png")); }
            catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
            finally { image.close(); }
        });
    }
}
