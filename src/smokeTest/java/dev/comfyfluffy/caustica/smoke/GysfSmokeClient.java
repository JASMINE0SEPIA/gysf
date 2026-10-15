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
    private static final boolean TEXTURE_FIXTURE=Boolean.getBoolean("gysf.smoke.textureFixture");
    private static final boolean VISUAL_ONLY=TEXTURE_FIXTURE && Boolean.getBoolean("gysf.smoke.visualOnly");
    private static final boolean VISUAL_CONTROL=TEXTURE_FIXTURE && (VISUAL_ONLY || Boolean.getBoolean("gysf.smoke.visualControl"));
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
            client.options.fov().set(70);
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
        if (worldTicks==250) client.player.connection.sendCommand(TEXTURE_FIXTURE
                ? "tp @s 0 140 128 180 20" : "tp @s 128 140 0 90 20");
        if (worldTicks==300 && TEXTURE_FIXTURE) {
            String[] commands={
                    "fill -24 109 94 24 118 108 minecraft:air",
                    "fill -24 108 88 24 108 120 minecraft:stone_bricks",
                    "fill -22 109 100 -15 116 100 minecraft:oak_leaves[persistent=true,distance=1]",
                    "fill -11 109 100 -4 116 100 minecraft:oak_planks",
                    "fill 0 109 100 7 116 100 minecraft:bricks",
                    "fill 11 109 100 18 116 100 minecraft:glass",
                    "fill -22 109 112 18 109 116 minecraft:stone_slab[type=bottom]"};
            for(String command:commands) client.player.connection.sendCommand(command);
            System.out.println("GYSF_SMOKE: placed isolated textured LOD fixture at z=100");
        }
        if (worldTicks==500) {
            client.player.connection.sendCommand(TEXTURE_FIXTURE ? "tp @s 0 140 0 0 16" : "tp @s 0 140 0 0 20");
            if(TEXTURE_FIXTURE) client.options.fov().set(30);
        }
        if (worldTicks==890 && TEXTURE_FIXTURE) {
            System.out.println("GYSF_SMOKE: fixture near-ready="+
                    dev.comfyfluffy.caustica.rt.terrain.RtTerrain.isSectionReady(new net.minecraft.core.BlockPos(0,112,100)));
        }
        if (worldTicks==900) Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(),
                image->{try {Files.createDirectories(Path.of("screenshots"));image.writeToFile(Path.of("screenshots","gysf-joint.png"));} catch(Exception e){throw new RuntimeException(e);} finally {image.close();}});
        if (VISUAL_CONTROL && worldTicks==920) System.setProperty("gysf.voxy.hideTextured","true");
        if (VISUAL_CONTROL && worldTicks==950) Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(),
                image->{try {image.writeToFile(Path.of("screenshots","gysf-no-textured-control.png"));} catch(Exception e){throw new RuntimeException(e);} finally {image.close();}});
        if (VISUAL_CONTROL && worldTicks==980) System.clearProperty("gysf.voxy.hideTextured");
        if (worldTicks==1000) reload=client.reloadResourcePacks();
        if (worldTicks==1350) client.player.connection.sendCommand("execute in minecraft:the_nether run tp @s 0 90 0");
        if (worldTicks==1650) client.player.connection.sendCommand("execute in minecraft:overworld run tp @s 0 140 0");
        if (worldTicks==(VISUAL_ONLY?990:2200)) {
            if(!VISUAL_ONLY) {
                if (reload==null || !reload.isDone()) throw new IllegalStateException("Resource reload incomplete");
                reload.join();
                if (!visitedNether || !returnedOverworld) throw new IllegalStateException("Dimension round trip incomplete");
            }
            if (!RtComposite.enabled() || RtComposite.frameCounter()<100) throw new IllegalStateException("RT frames missing");
            if (Boolean.getBoolean("gysf.smoke.vooxxy") && (!VoxyCompat.enabled() || maxMeshes==0 || maxRtSections==0)) throw new IllegalStateException("External ray-traced LOD geometry missing");
            long textured=dev.comfyfluffy.caustica.rt.terrain.RtDistantHorizonsTerrain.INSTANCE.texturedBuildCount();
            long leafCutouts=dev.comfyfluffy.caustica.rt.terrain.RtDistantHorizonsTerrain.INSTANCE.leafCutoutQuadCount();
            if (Boolean.getBoolean("gysf.smoke.vooxxy") && !Boolean.getBoolean("gysf.voxy.colourProxy")
                    && (textured==0 || VoxyCompat.texturedMeshCount()==0 || VoxyCompat.boundAtlasSlot()==0))
                throw new IllegalStateException("Textured Voxy geometry or atlas binding missing");
            if(TEXTURE_FIXTURE && !Boolean.getBoolean("gysf.voxy.colourProxy") && leafCutouts==0)
                throw new IllegalStateException("Foliage alpha-cutout metadata missing");
            System.out.println("GYSF_SMOKE: PASS frames="+RtComposite.frameCounter()+" maxVoxyMeshes="+maxMeshes+" maxRtLodSections="+maxRtSections+" texturedBatches="+textured+" atlasSlot="+VoxyCompat.boundAtlasSlot()+" leafCutoutQuads="+leafCutouts);
            finished=true;
            client.disconnectWithSavingScreen();
        }
    }
}
