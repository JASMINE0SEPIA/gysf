package dev.comfyfluffy.caustica.smoke;

import dev.comfyfluffy.caustica.compat.VoxyCompat;
import dev.comfyfluffy.caustica.rt.RtComposite;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import java.nio.file.*;

/** Automatic Vulkan scene check in an isolated development world. */
public final class GysfSmokeClient implements ClientModInitializer {
    private static final String WORLD="gysf-26.3-smoke";
    private int ticks,worldTicks,maxMeshes,maxRtSections;
    private boolean opened;
    private boolean finished;
    private boolean visitedNether, returnedOverworld;
    private int exitTicks;
    private java.util.concurrent.CompletableFuture<Void> reload;
    @Override public void onInitializeClient() { ClientTickEvents.END_CLIENT_TICK.register(this::tick); }
    private void tick(Minecraft client) {
        ticks++;
        if (finished) { if (++exitTicks>40) client.stop(); return; }
        if (ticks>20000) throw new IllegalStateException("Smoke timed out");
        if (!opened && client.gui.screen() instanceof TitleScreen && ticks>40) {
            opened=true;
            client.options.pauseOnLostFocus=false;
            client.options.renderDistance().set(4);
            client.getWindow().setWindowed(854,480);
            var flows=client.createWorldOpenFlows();
            if (Files.exists(Path.of("saves",WORLD,"level.dat"))) flows.openWorld(WORLD,()->{throw new IllegalStateException("World open cancelled");});
            else flows.createFreshLevel(WORLD,new LevelSettings("gysf isolated Vulkan smoke",GameType.CREATIVE,
                    new LevelSettings.DifficultySettings(Difficulty.PEACEFUL,false,false),true,WorldDataConfiguration.DEFAULT),
                    new WorldOptions(2632026L,true,false),WorldPresets::createNormalWorldDimensions,new TitleScreen());
            System.out.println("GYSF_SMOKE: opening isolated world");
        }
        if (client.level==null || client.player==null) return;
        worldTicks++;
        if (client.level.dimension().equals(net.minecraft.world.level.Level.NETHER)) visitedNether=true;
        if (visitedNether && client.level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) returnedOverworld=true;
        if (RtComposite.INSTANCE.hasFailed()) throw new IllegalStateException("RT compositor failed");
        maxMeshes=Math.max(maxMeshes,VoxyCompat.meshes().size());
        maxRtSections=Math.max(maxRtSections,dev.comfyfluffy.caustica.rt.terrain.RtDistantHorizonsTerrain.INSTANCE.residentSectionCount());
        if (worldTicks==80) { client.player.connection.sendCommand("gamemode spectator"); client.player.connection.sendCommand("tp @s 0 140 0 0 20"); client.player.connection.sendCommand("time set noon"); }
        if (worldTicks==250) client.player.connection.sendCommand("tp @s 128 140 0 90 20");
        if (worldTicks==500) client.player.connection.sendCommand("tp @s 0 140 0 0 20");
        if (worldTicks==900) Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(),
                image->{try {Files.createDirectories(Path.of("screenshots"));image.writeToFile(Path.of("screenshots","gysf-joint.png"));} catch(Exception e){throw new RuntimeException(e);} finally {image.close();}});
        if (worldTicks==1000) reload=client.reloadResourcePacks();
        if (worldTicks==1350) client.player.connection.sendCommand("execute in minecraft:the_nether run tp @s 0 90 0");
        if (worldTicks==1650) client.player.connection.sendCommand("execute in minecraft:overworld run tp @s 0 140 0");
        if (worldTicks==2200) {
            if (reload==null || !reload.isDone()) throw new IllegalStateException("Resource reload incomplete");
            reload.join();
            if (!visitedNether || !returnedOverworld) throw new IllegalStateException("Dimension round trip incomplete");
            if (!RtComposite.enabled() || RtComposite.frameCounter()<100) throw new IllegalStateException("RT frames missing");
            if (Boolean.getBoolean("gysf.smoke.vooxxy") && (!VoxyCompat.enabled() || maxMeshes==0 || maxRtSections==0)) throw new IllegalStateException("External ray-traced LOD geometry missing");
            System.out.println("GYSF_SMOKE: PASS frames="+RtComposite.frameCounter()+" maxVoxyMeshes="+maxMeshes+" maxRtLodSections="+maxRtSections);
            finished=true;
            client.disconnectWithSavingScreen();
        }
    }
}
