package dev.comfyfluffy.caustica.smoke;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.rt.RtComposite;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;

/** Repeatable rain/light/roof comparisons in a physical save copy only. */
public final class WeatherCapture {
    public static boolean locked() { return Boolean.getBoolean("gysf.smoke.weather"); }
    public static void enforceCamera() {
        if (!locked()) return;
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        player.setYRot(0); player.yRotO = 0;
        player.setXRot(12); player.xRotO = 12;
        player.setYHeadRot(0); player.yHeadRotO = 0;
    }

    private int ticks, worldTicks, exitTicks;
    private boolean opened, finished;
    private float minimumRain = 1, maximumRain;
    private int maximumColumns;
    private final Map<String, Object> states = new LinkedHashMap<>();

    void tick(Minecraft client) {
        if (++ticks > 6000) throw new IllegalStateException("Weather replay timed out");
        if (finished) { if (++exitTicks > 40) client.stop(); return; }
        if (!opened && client.gui.screen() instanceof TitleScreen && ticks > 50) {
            Path world = Path.of("saves", "gysf-weather").toAbsolutePath().normalize();
            if (!world.startsWith(Path.of(".").toAbsolutePath().normalize()) || !Files.exists(world.resolve("level.dat")))
                throw new IllegalStateException("Weather replay requires an isolated physical save copy");
            opened = true;
            client.options.pauseOnLostFocus = false;
            client.options.enableVsync().set(false);
            client.options.framerateLimit().set(60);
            client.options.renderDistance().set(6);
            client.options.simulationDistance().set(6);
            client.options.fov().set(70);
            client.getWindow().setWindowed(1280, 800);
            client.createWorldOpenFlows().openWorld("gysf-weather", () -> { throw new IllegalStateException("Weather replay cancelled"); });
        }
        if (client.level == null || client.player == null) return;
        if (RtComposite.INSTANCE.hasFailed()) throw new IllegalStateException("RT failed in weather replay");
        ++worldTicks;
        float rain = client.level.getRainLevel(1);
        minimumRain = Math.min(minimumRain, rain); maximumRain = Math.max(maximumRain, rain);
        maximumColumns = Math.max(maximumColumns, client.gameRenderer.gameRenderState().levelRenderState.weatherRenderState.rainColumns.size());
        switch (worldTicks) {
            case 60 -> {
                command(client, "gamemode spectator");
                command(client, "gamerule minecraft:advance_time false");
                command(client, "time set 6000");
                command(client, "weather clear 1000000");
                // A small neutral courtyard with an opaque wall and a roof. Only the copy is edited.
                command(client, "fill 162 180 -291 194 195 -265 air");
                command(client, "fill 162 180 -291 194 180 -265 stone");
                command(client, "fill 162 181 -265 194 193 -265 gray_concrete");
                command(client, "fill 163 190 -289 172 190 -273 stone");
                command(client, "tp @s 178.5 184 -284.5 0 12");
                CausticaConfig.Rt.Entities.RAIN_DENSITY.set(1f);
            }
            case 350 -> capture(client, "01-clear-day");
            case 360 -> command(client, "weather rain 1000000");
            case 650 -> capture(client, "02-rain-full");
            case 660 -> CausticaConfig.Rt.Entities.RAIN_DENSITY.set(0.25f);
            case 850 -> capture(client, "03-rain-quarter");
            case 860 -> {
                CausticaConfig.Rt.Entities.RAIN_DENSITY.set(1f);
                command(client, "time set 18000");
            }
            case 1070 -> capture(client, "04-night-dark");
            case 1080 -> {
                command(client, "fill 175 184 -281 181 184 -281 sea_lantern");
                command(client, "fill 175 184 -280 181 184 -280 sea_lantern");
            }
            case 1290 -> capture(client, "05-night-lit");
            case 1300 -> {
                command(client, "time set 6000");
                command(client, "tp @s 167.5 184 -284.5 0 12");
            }
            case 1510 -> capture(client, "06-under-roof");
            case 1520 -> command(client, "weather clear 1000000");
            case 1730 -> {
                capture(client, "07-rain-stopped");
                if (maximumRain < 0.99f || minimumRain > 0.01f || maximumColumns < 20 || rain > 0.01f
                        || RtComposite.frameCounter() < 300)
                    throw new IllegalStateException("Insufficient rain/weather transition coverage");
                try {
                    Files.writeString(Path.of("weather-states.json"), new com.google.gson.GsonBuilder()
                            .setPrettyPrinting().create().toJson(states));
                } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                System.out.println("GYSF_WEATHER: PASS captures=" + states.size() + " columns=" + maximumColumns
                        + " rain=" + minimumRain + ".." + maximumRain + " frames=" + RtComposite.frameCounter());
                client.disconnectWithSavingScreen(); finished = true;
            }
        }
    }

    private void capture(Minecraft client, String name) {
        var weather = client.gameRenderer.gameRenderState().levelRenderState.weatherRenderState;
        var state = new LinkedHashMap<String, Object>();
        state.put("rain", weather.intensity); state.put("density", CausticaConfig.Rt.Entities.RAIN_DENSITY.value());
        state.put("columns", weather.rainColumns.size()); state.put("gameTime", client.level.getGameTime());
        state.put("camera", java.util.List.of(client.player.getX(), client.player.getY(), client.player.getZ()));
        int maximumBlockLight = 0;
        for (var column : weather.rainColumns) maximumBlockLight = Math.max(maximumBlockLight, (column.lightCoords() >> 4) & 15);
        state.put("maximumBlockLight", maximumBlockLight);
        states.put(name, state);
        System.out.println("GYSF_WEATHER: " + name + " " + state);
        Path folder = Path.of("weather-captures");
        try { Files.createDirectories(folder); } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try { image.writeToFile(folder.resolve(name + ".png")); }
            catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            finally { image.close(); }
        });
    }
    private static void command(Minecraft client, String command) { client.player.connection.sendCommand(command); }
}
