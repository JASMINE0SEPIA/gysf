package dev.comfyfluffy.caustica.smoke;

import dev.comfyfluffy.caustica.compat.VoxyCompat;
import dev.comfyfluffy.caustica.CausticaConfig;
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
    private static final boolean CLEAN_WATER=Boolean.getBoolean("gysf.smoke.cleanWater");
    private static final boolean PERFORMANCE=Boolean.getBoolean("gysf.smoke.performance");
    private static final String WORLD=Boolean.getBoolean("gysf.smoke.fluidMip")?"gysf-fluid-mip-26.3"
            :CLEAN_WATER?"gysf-clean-water-26.3":"gysf-26.3-smoke";
    private static final boolean TEXTURE_FIXTURE=Boolean.getBoolean("gysf.smoke.textureFixture");
    private static final boolean WATER_MATRIX=Boolean.getBoolean("gysf.smoke.waterMatrix");
    private static final boolean WATER_COMPARE=Boolean.getBoolean("gysf.smoke.waterCompareOnly");
    private static final boolean WATER_NEAR_REFERENCE=Boolean.getBoolean("gysf.smoke.waterNearReference");
    private static final boolean STATIC_WATER=WATER_MATRIX || WATER_COMPARE || Boolean.getBoolean("gysf.smoke.staticWaterFixture");
    private static final boolean WATER_FIXTURE=CLEAN_WATER || WATER_MATRIX || WATER_COMPARE || Boolean.getBoolean("gysf.smoke.waterFixture");
    private static final boolean MOTION=Boolean.getBoolean("gysf.smoke.motion");
    private static final boolean WATER_DOMAIN_MOTION=Boolean.getBoolean("gysf.smoke.waterDomainMotion");
    private static final boolean MEMORY_PRESSURE=Boolean.getBoolean("gysf.smoke.memoryPressure");
    private static final boolean FLUID_TOPOLOGY=Boolean.getBoolean("gysf.smoke.fluidTopology");
    private static final boolean FLUID_TOPOLOGY_EDITS=Boolean.getBoolean("gysf.smoke.fluidTopologyEdits");
    private static final boolean FLUID_MIP=Boolean.getBoolean("gysf.smoke.fluidMip");
    private static final boolean VISUAL_ONLY=TEXTURE_FIXTURE && Boolean.getBoolean("gysf.smoke.visualOnly");
    private static final boolean VISUAL_CONTROL=TEXTURE_FIXTURE && (VISUAL_ONLY || Boolean.getBoolean("gysf.smoke.visualControl"));
    private int ticks,worldTicks,maxMeshes,maxRtSections;
    private boolean opened;
    private boolean finished;
    private boolean visitedNether, returnedOverworld;
    private int exitTicks;
    private double motionMinX=Double.POSITIVE_INFINITY,motionMaxX=Double.NEGATIVE_INFINITY;
    private double motionMinY=Double.POSITIVE_INFINITY,motionMaxY=Double.NEGATIVE_INFINITY;
    private int terrainMinX=Integer.MAX_VALUE,terrainMaxX=Integer.MIN_VALUE;
    private String originalMemoryLimit;
    private long maxMemoryDeferrals;
    private boolean sawDriverBudget, observedBudgetRecovery;
    private int maxNearWaterInstances,maxLodWaterInstances;
    private int pressureFallbackFrames;
    private boolean observedReducedProvider;
    private java.util.concurrent.CompletableFuture<Void> reload;
    private java.util.concurrent.CompletableFuture<Boolean> waterFixtureReady;
    private java.util.concurrent.CompletableFuture<?> fluidTopologyReady;
    private java.util.concurrent.CompletableFuture<?> fluidTopologyEditReady;
    private java.util.concurrent.CompletableFuture<?> fluidTopologyRestoreReady;
    private java.util.concurrent.CompletableFuture<?> fluidMipReady;
    private final WaterSequenceCapture waterSequence=new WaterSequenceCapture();
    private final WaterCameraCapture waterCamera=new WaterCameraCapture();
    private final WaterDomainSmokeProbe waterDomain=new WaterDomainSmokeProbe();
    private final WaterGuideCapture waterGuides=new WaterGuideCapture();
    @Override public void onInitializeClient() {
        if(WaterGuideCapture.ENABLED&&(!CLEAN_WATER||!WATER_COMPARE||FLUID_MIP||FLUID_TOPOLOGY||WATER_MATRIX
                ||WaterCameraCapture.ENABLED||WaterSequenceCapture.ENABLED||!dev.comfyfluffy.caustica.rt.RtGuideCapture.enabled()))
            throw new IllegalStateException("Water guides require a separate clean comparison and raw readback enabled");
        if(WaterGuideCapture.OPTICS&&(!WaterGuideCapture.ENABLED||!Boolean.getBoolean("gysf.rt.captureResolvedGuides")))
            throw new IllegalStateException("Water optics requires guide capture and resolved HDR readback");
        if(WaterDomainSmokeProbe.ENABLED && !FLUID_MIP)
            throw new IllegalStateException("Water domain probe needs the isolated fluid-mip pool");
        if(FLUID_MIP && (!CLEAN_WATER || !WATER_COMPARE || FLUID_TOPOLOGY || FLUID_TOPOLOGY_EDITS
                || WaterCameraCapture.ENABLED || WaterSequenceCapture.ENABLED))
            throw new IllegalStateException("Fluid mip probe needs its own clean-water comparison run");
        if(CLEAN_WATER && (FLUID_TOPOLOGY || FLUID_TOPOLOGY_EDITS || TEXTURE_FIXTURE))
            throw new IllegalStateException("Clean-water world cannot contain topology or material fixtures");
        if(WaterCameraCapture.ENABLED && (!CLEAN_WATER || !WATER_COMPARE || WATER_MATRIX || WaterSequenceCapture.ENABLED
                || !dev.comfyfluffy.caustica.rt.terrain.RtWaterGeometry.runtimeEnabled()
                || !dev.comfyfluffy.caustica.rt.RtWaterCameraProbe.validationEnabled()))
            throw new IllegalStateException("Water camera checks need finite water, GPU readback and clean comparison mode");
        if(WaterSequenceCapture.ENABLED && (!CLEAN_WATER || !WATER_COMPARE || WATER_MATRIX || requestedWaveSpeed()<=0))
            throw new IllegalStateException("Animated water sequence needs clean comparison mode and positive speed");
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }
    private void tick(Minecraft client) {
        ticks++;
        if (finished) { if (++exitTicks>40) client.stop(); return; }
        if (ticks>20000) throw new IllegalStateException("Smoke timed out");
        if (!opened && client.gui.screen() instanceof TitleScreen && ticks>40) {
            opened=true;
            client.options.pauseOnLostFocus=false;
            client.options.renderDistance().set(Integer.getInteger("gysf.smoke.renderDistance",PERFORMANCE?32:WATER_NEAR_REFERENCE?12:4));
            client.options.fov().set(70);
            client.getWindow().setWindowed(Integer.getInteger("gysf.smoke.width",PERFORMANCE?1792:854),
                    Integer.getInteger("gysf.smoke.height",PERFORMANCE?1120:480));
            if(PERFORMANCE) {
                client.options.enableVsync().set(false);
                client.options.framerateLimit().set(260);
                client.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
                client.options.simulationDistance().set(24);
            }
            var flows=client.createWorldOpenFlows();
            if (Files.exists(Path.of("saves",WORLD,"level.dat"))) flows.openWorld(WORLD,()->{throw new IllegalStateException("World open cancelled");});
            else flows.createFreshLevel(WORLD,new LevelSettings("gysf isolated Vulkan smoke",GameType.CREATIVE,
                    new LevelSettings.DifficultySettings(Difficulty.PEACEFUL,false,false),true,WorldDataConfiguration.DEFAULT),
                    new WorldOptions(2632026L,true,false),WorldPresets::createNormalWorldDimensions,new TitleScreen());
            System.out.println("GYSF_SMOKE: opening isolated world");
        }
        if (client.level==null || client.player==null) return;
        worldTicks++;
        if(WaterCacheLifecycle.ENABLED) {
            if(WaterCacheLifecycle.tick(client,worldTicks)){finished=true;client.disconnectWithSavingScreen();}
            return;
        }
        if(PERFORMANCE) {
            if(PerformanceCapture.tick(client,worldTicks)) { finished=true;client.disconnectWithSavingScreen(); }
            return;
        }
        if (MOTION && worldTicks>=1750 && worldTicks<=2300) {
            motionMinX=Math.min(motionMinX,client.player.getX()); motionMaxX=Math.max(motionMaxX,client.player.getX());
            motionMinY=Math.min(motionMinY,client.player.getY()); motionMaxY=Math.max(motionMaxY,client.player.getY());
            var near=dev.comfyfluffy.caustica.rt.terrain.RtTerrain.currentOrNull();
            if(near!=null) { terrainMinX=Math.min(terrainMinX,near.blockX); terrainMaxX=Math.max(terrainMaxX,near.blockX); }
        }
        if (client.level.dimension().equals(net.minecraft.world.level.Level.NETHER)) visitedNether=true;
        if (visitedNether && client.level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) returnedOverworld=true;
        if (RtComposite.INSTANCE.hasFailed()) throw new IllegalStateException("RT compositor failed");
        maxMeshes=Math.max(maxMeshes,VoxyCompat.meshes().size());
        maxRtSections=Math.max(maxRtSections,dev.comfyfluffy.caustica.rt.terrain.RtDistantHorizonsTerrain.INSTANCE.residentSectionCount());
        if(dev.comfyfluffy.caustica.rt.terrain.RtWaterGeometry.runtimeEnabled()) {
            var near=dev.comfyfluffy.caustica.rt.terrain.RtTerrain.currentOrNull();
            if(near!=null && near.staticInstances()!=null) {
                int count=0;for(var instance:near.staticInstances())if(instance.waterBlasDeviceAddress()!=0)count++;
                maxNearWaterInstances=Math.max(maxNearWaterInstances,count);
            }
            maxLodWaterInstances=Math.max(maxLodWaterInstances,
                    dev.comfyfluffy.caustica.rt.terrain.RtDistantHorizonsTerrain.INSTANCE.waterInstanceCount());
        }
        var memoryContext=dev.comfyfluffy.caustica.rt.RtContext.currentOrNull();
        if(memoryContext!=null) {
            maxMemoryDeferrals=Math.max(maxMemoryDeferrals,memoryContext.lodMemory().deferralCount());
            sawDriverBudget|=memoryContext.memoryBudget().driverReported();
        }
        if(MEMORY_PRESSURE && worldTicks==400) {
            originalMemoryLimit=System.getProperty("gysf.voxy.memoryBudgetMiB");
            System.setProperty("gysf.voxy.memoryBudgetMiB","1");
            dev.comfyfluffy.caustica.rt.terrain.RtDistantHorizonsTerrain.INSTANCE.requestFullRefresh();
            System.out.println("GYSF_SMOKE: forced LOD allocation budget to 1 MiB");
        }
        if(MEMORY_PRESSURE && worldTicks==600) {
            if(originalMemoryLimit==null) System.clearProperty("gysf.voxy.memoryBudgetMiB");
            else System.setProperty("gysf.voxy.memoryBudgetMiB",originalMemoryLimit);
            System.out.println("GYSF_SMOKE: restored original LOD allocation budget");
        }
        if(MEMORY_PRESSURE && worldTicks>600 && worldTicks<1000) {
            var memoryLod=dev.comfyfluffy.caustica.rt.terrain.RtDistantHorizonsTerrain.INSTANCE;
            observedBudgetRecovery|=memoryLod.memoryRecoveryCount()>0 && memoryLod.memoryDistanceLimit()==0;
        }
        if(MEMORY_PRESSURE && worldTicks>400 && worldTicks<600 && maxMemoryDeferrals>0) {
            if(dev.comfyfluffy.caustica.rt.terrain.RtDistantHorizonsTerrain.INSTANCE.residentSectionCount()>0)
                pressureFallbackFrames++;
            observedReducedProvider|=VoxyCompat.renderDistanceChunks()*16<VoxyCompat.requestedDistanceBlocks();
        }
        if (worldTicks==80) { client.player.connection.sendCommand("gamemode spectator"); client.player.connection.sendCommand("execute in minecraft:overworld run tp @s 0 140 0 0 20"); client.player.connection.sendCommand("time set noon"); }
        if (worldTicks==80 && STATIC_WATER) CausticaConfig.Rt.Composite.WATER_WAVE_SPEED.set(0f);
        if (worldTicks==80 && CLEAN_WATER) {
            CausticaConfig.Rt.Composite.CLOUDS.set(false);
            CausticaConfig.Rt.Exposure.MODE.set("manual");
            CausticaConfig.Rt.Exposure.MANUAL_EV.set(0f);
            System.out.println("GYSF_CLEAN_WATER: manual EV=0, clouds off, topology/material fixtures forbidden");
        }
        if(worldTicks==80 && (System.getProperty("gysf.smoke.waveStrength")!=null || System.getProperty("gysf.smoke.waveSpeed")!=null)) {
            float strength=Float.parseFloat(System.getProperty("gysf.smoke.waveStrength","1.0"));
            if(!Float.isFinite(strength) || strength<0 || strength>4) throw new IllegalStateException("Invalid smoke wave strength");
            CausticaConfig.Rt.Composite.WATER_WAVES.set(true);
            CausticaConfig.Rt.Composite.WATER_WAVE_STRENGTH.set(strength);
            if(!STATIC_WATER) CausticaConfig.Rt.Composite.WATER_WAVE_SPEED.set(requestedWaveSpeed());
            System.out.println("GYSF_SMOKE: water waves strength="+strength+" speed="+CausticaConfig.Rt.Composite.WATER_WAVE_SPEED.value());
        }
        if (worldTicks==250) {
            if(WATER_FIXTURE) client.player.connection.sendCommand("forceload add 32 0 96 160");
            client.player.connection.sendCommand(WATER_FIXTURE ? "tp @s 64 116 128 180 5" : TEXTURE_FIXTURE
                    ? "tp @s 0 140 128 180 20" : "tp @s 128 140 0 90 20");
        }
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
        if (worldTicks==300 && WATER_FIXTURE) {
            String[] commands={
                    "fill 48 112 16 79 118 144 minecraft:air",
                    "fill 47 108 15 80 108 145 minecraft:quartz_block",
                    "fill 47 109 15 47 111 145 minecraft:quartz_block",
                    "fill 80 109 15 80 111 145 minecraft:quartz_block",
                    "fill 48 109 15 79 111 15 minecraft:quartz_block",
                    "fill 48 109 145 79 111 145 minecraft:quartz_block",
                    "fill 48 109 16 79 111 144 minecraft:water[level=0]"};
            for(String command:commands) client.player.connection.sendCommand(command);
            System.out.println("GYSF_SMOKE: placed isolated water fixture across 16-block section boundaries");
            if(CLEAN_WATER) for(int y=112;y<=143;y+=7)
                client.player.connection.sendCommand("fill 47 "+y+" 15 80 "+Math.min(y+6,143)+" 145 minecraft:air");
        }
        if (worldTicks==500) {
            client.player.connection.sendCommand(WATER_FIXTURE ? "tp @s 64 140 0 0 16" : TEXTURE_FIXTURE ? "tp @s 0 140 0 0 16" : "tp @s 0 140 0 0 20");
            if(TEXTURE_FIXTURE || WATER_FIXTURE) client.options.fov().set(30);
        }
        if(worldTicks==350 && FLUID_TOPOLOGY) {
            var server=client.getSingleplayerServer();
            if(server==null) throw new IllegalStateException("Topology fixture needs isolated integrated server");
            fluidTopologyReady=server.submit(()->dev.comfyfluffy.caustica.rt.terrain.FluidTopologySmokeProbe.place(server));
        }
        if(worldTicks==350 && FLUID_MIP) {
            var server=client.getSingleplayerServer();
            if(server==null) throw new IllegalStateException("Fluid mip fixture needs isolated integrated server");
            fluidMipReady=server.submit(()->dev.comfyfluffy.caustica.rt.terrain.FluidMipSmokeProbe.place(server));
        }
        if(worldTicks==1200 && FLUID_MIP) {
            if(fluidMipReady==null || !fluidMipReady.isDone()) throw new IllegalStateException("Fluid mip fixture incomplete");
            fluidMipReady.join();
            dev.comfyfluffy.caustica.rt.terrain.FluidMipSmokeProbe.verify(client);
        }
        if (worldTicks==890 && TEXTURE_FIXTURE) {
            System.out.println("GYSF_SMOKE: fixture near-ready="+
                    dev.comfyfluffy.caustica.rt.terrain.RtTerrain.isSectionReady(new net.minecraft.core.BlockPos(0,112,100)));
        }
        if (worldTicks==800 && WATER_FIXTURE) {
            var server=client.getSingleplayerServer();
            if(server==null) throw new IllegalStateException("Isolated integrated server missing");
            waterFixtureReady=server.submit(()->{
                var level=server.overworld();
                for(int z=16;z<=144;z++) {
                    if(!level.getFluidState(new net.minecraft.core.BlockPos(64,110,z)).is(net.minecraft.tags.FluidTags.WATER)
                            || !level.getBlockState(new net.minecraft.core.BlockPos(64,108,z)).is(net.minecraft.world.level.block.Blocks.QUARTZ_BLOCK)) return false;
                }
                if(CLEAN_WATER) for(int x=47;x<=80;x++) for(int z=15;z<=145;z++) for(int y=112;y<=143;y++)
                    if(!level.getBlockState(new net.minecraft.core.BlockPos(x,y,z)).isAir()) return false;
                return true;
            });
        }
        if (worldTicks==890 && WATER_FIXTURE) {
            if(waterFixtureReady==null || !waterFixtureReady.isDone() || !waterFixtureReady.join())
                throw new IllegalStateException("Water fixture blocks were not placed across the complete test lane");
            if(CLEAN_WATER) System.out.println("GYSF_CLEAN_WATER: verified air above pool through Y=143; isolated server frozen");
            boolean near=dev.comfyfluffy.caustica.rt.terrain.RtTerrain.isSectionReady(new net.minecraft.core.BlockPos(64,110,32));
            boolean far=dev.comfyfluffy.caustica.rt.terrain.RtTerrain.isSectionReady(new net.minecraft.core.BlockPos(64,110,128));
            if(!near || far!=WATER_NEAR_REFERENCE) throw new IllegalStateException("Unexpected water near/LOD ownership: "+near+"/"+far+", reference="+WATER_NEAR_REFERENCE);
            int ready=0;
            for(int z=16;z<=144;z++) if(dev.comfyfluffy.caustica.rt.terrain.RtTerrain.isSectionReady(new net.minecraft.core.BlockPos(64,110,z))) ready++;
            if(WATER_NEAR_REFERENCE && ready!=129) throw new IllegalStateException("Near-reference pool lane has unpublished sections: "+ready+"/129");
            System.out.println("GYSF_SMOKE: water near-ready="+near+" far-ready="+far);
            System.out.println("GYSF_SMOKE: water lane near-ready="+ready+"/129");
            dev.comfyfluffy.caustica.rt.terrain.FluidHeightSmokeProbe.verify(Boolean.getBoolean("gysf.smoke.requirePreciseFluids"));
            if(FLUID_TOPOLOGY) {
                if(fluidTopologyReady==null || !fluidTopologyReady.isDone()) throw new IllegalStateException("Topology fixture incomplete");
                fluidTopologyReady.join();
                dev.comfyfluffy.caustica.rt.terrain.FluidTopologySmokeProbe.verify(client,Boolean.getBoolean("gysf.smoke.topologyAllowMismatch"));
            }
        }
        if(CLEAN_WATER && worldTicks==850) client.getSingleplayerServer().submit(()->client.getSingleplayerServer().tickRateManager().setFrozen(true));
        if (worldTicks==900) Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(),
                image->{try {Files.createDirectories(Path.of("screenshots"));image.writeToFile(Path.of("screenshots","gysf-joint.png"));} catch(Exception e){throw new RuntimeException(e);} finally {image.close();}});
        if(worldTicks==950 && FLUID_TOPOLOGY_EDITS) {
            if(!FLUID_TOPOLOGY || !WATER_COMPARE) throw new IllegalStateException("Topology edits need the isolated topology comparison mode");
            var server=client.getSingleplayerServer();
            fluidTopologyEditReady=server.submit(()->dev.comfyfluffy.caustica.rt.terrain.FluidTopologySmokeProbe.editDiagonalCeiling(server));
        }
        if(worldTicks==1200 && FLUID_TOPOLOGY_EDITS) {
            if(fluidTopologyEditReady==null || !fluidTopologyEditReady.isDone()) throw new IllegalStateException("Topology edit incomplete");
            fluidTopologyEditReady.join();
            dev.comfyfluffy.caustica.rt.terrain.FluidTopologySmokeProbe.verify(client,Boolean.getBoolean("gysf.smoke.topologyAllowMismatch"));
        }
        if(worldTicks==1220 && FLUID_TOPOLOGY_EDITS) {
            var server=client.getSingleplayerServer();
            fluidTopologyRestoreReady=server.submit(()->dev.comfyfluffy.caustica.rt.terrain.FluidTopologySmokeProbe.restoreDiagonalCeiling(server));
        }
        if(worldTicks==1500 && FLUID_TOPOLOGY_EDITS) {
            if(fluidTopologyRestoreReady==null || !fluidTopologyRestoreReady.isDone()) throw new IllegalStateException("Topology restoration incomplete");
            fluidTopologyRestoreReady.join();
            dev.comfyfluffy.caustica.rt.terrain.FluidTopologySmokeProbe.verify(client,Boolean.getBoolean("gysf.smoke.topologyAllowMismatch"));
        }
        if (VISUAL_CONTROL && worldTicks==920) System.setProperty("gysf.voxy.hideTextured","true");
        if (VISUAL_CONTROL && worldTicks==950) Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(),
                image->{try {image.writeToFile(Path.of("screenshots","gysf-no-textured-control.png"));} catch(Exception e){throw new RuntimeException(e);} finally {image.close();}});
        if (VISUAL_CONTROL && worldTicks==980) System.clearProperty("gysf.voxy.hideTextured");
        if (worldTicks==1000 && !WATER_COMPARE && !(CLEAN_WATER && WATER_MATRIX)) reload=client.reloadResourcePacks();
        if (WATER_FIXTURE && !WaterCameraCapture.ENABLED && !WaterGuideCapture.ENABLED && worldTicks==1100) {
            client.player.connection.sendCommand("tp @s 64 110 32 0 -2");
            client.options.fov().set(70);
            if(WATER_MATRIX) CausticaConfig.Rt.Composite.WATER_WAVE_SPEED.set(0f);
        }
        if (WATER_FIXTURE && !WaterCameraCapture.ENABLED && !WaterGuideCapture.ENABLED && worldTicks==1250) {
            if(!client.player.isUnderWater()) throw new IllegalStateException("Underwater camera was not observed");
            Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(),
                    image->{try {image.writeToFile(Path.of("screenshots","gysf-water-underwater.png"));} catch(Exception e){throw new RuntimeException(e);} finally {image.close();}});
            System.out.println("GYSF_SMOKE: underwater water fixture captured");
            System.out.println("GYSF_SMOKE: underwater resident LOD sections="+dev.comfyfluffy.caustica.rt.terrain.RtDistantHorizonsTerrain.INSTANCE.residentSectionCount());
            if(WATER_MATRIX) waterCapture(client,"baseline");
        }
        if(WATER_MATRIX) {
            if(worldTicks==1260) System.setProperty("gysf.voxy.hideTextured","true");
            if(worldTicks==1340) waterCapture(client,"no-lod");
            if(worldTicks==1345) { System.clearProperty("gysf.voxy.hideTextured"); CausticaConfig.Rt.Composite.WATER_WAVES.set(false); }
            if(worldTicks==1425) waterCapture(client,"flat");
            if(worldTicks==1430) { CausticaConfig.Rt.Composite.WATER_WAVES.set(true); CausticaConfig.Rt.Composite.MAX_BOUNCES.set(8); }
            if(worldTicks==1510) waterCapture(client,"eight-bounces");
            if(worldTicks==1515) CausticaConfig.Rt.Composite.DENOISER.set(false);
            if(worldTicks==1595) waterCapture(client,"raw-eight");
            if(worldTicks==1600) CausticaConfig.Rt.Composite.MAX_BOUNCES.set(2);
            if(worldTicks==1680) waterCapture(client,"raw-two");
            if(worldTicks==1685) { CausticaConfig.Rt.Composite.DENOISER.set(true); CausticaConfig.Rt.Composite.WATER_WAVE_SPEED.set(requestedWaveSpeed()); }
        }
        if (WATER_FIXTURE && worldTicks==(WaterGuideCapture.ENABLED?WaterGuideCapture.END_TICK-10:WaterCameraCapture.ENABLED?1960:WaterSequenceCapture.ENABLED?1570:CLEAN_WATER && WATER_MATRIX?1710:FLUID_TOPOLOGY_EDITS?1540:1280)) client.player.connection.sendCommand("forceload remove 32 0 96 160");
        if(FLUID_TOPOLOGY && worldTicks==(FLUID_TOPOLOGY_EDITS?1540:1280)) client.getSingleplayerServer().submit(()->client.getSingleplayerServer().tickRateManager().setFrozen(false));
        if (STATIC_WATER && !WATER_MATRIX && !WaterGuideCapture.ENABLED && worldTicks==1280) CausticaConfig.Rt.Composite.WATER_WAVE_SPEED.set(requestedWaveSpeed());
        if (!WATER_MATRIX && !WATER_COMPARE && worldTicks==1350) client.player.connection.sendCommand("execute in minecraft:the_nether run tp @s 0 90 0");
        if (!WATER_MATRIX && !WATER_COMPARE && worldTicks==1650) client.player.connection.sendCommand("execute in minecraft:overworld run tp @s 0 140 0");
        if (MOTION && worldTicks>=1750 && worldTicks<=2050 && worldTicks%10==0) {
            int step=(worldTicks-1750)/10;
            int x=WATER_DOMAIN_MOTION && step<20 ? (step<10?4096+(step-4)*64:-4096+(step-14)*64) : -256+step*24;
            client.player.connection.sendCommand("tp @s "+x+" "+(80+(step%8)*16)+" -128 0 20");
        }
        if (MOTION && worldTicks==1905)
            dev.comfyfluffy.caustica.rt.terrain.RtDistantHorizonsTerrain.INSTANCE.requestFullRefresh();
        if (MOTION && worldTicks==2100) client.player.connection.sendCommand("tp @s -384 96 384 180 20");
        if (MOTION && worldTicks==2300) client.player.connection.sendCommand("tp @s 0 140 0 0 16");
        if (MOTION && worldTicks==2800) Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(),
                image->{try {image.writeToFile(Path.of("screenshots","gysf-motion-return.png"));} catch(Exception e){throw new RuntimeException(e);} finally {image.close();}});
        waterSequence.sample(client,worldTicks);
        waterGuides.sample(client,worldTicks);
        waterCamera.sample(client,worldTicks);
        waterDomain.tick(client,worldTicks);
        if (worldTicks==(WATER_COMPARE?(WaterGuideCapture.ENABLED?WaterGuideCapture.END_TICK:WaterCameraCapture.ENABLED?1970:FLUID_TOPOLOGY_EDITS?1550:WaterSequenceCapture.ENABLED?1580:1290):WATER_MATRIX?1720:VISUAL_ONLY?990:MOTION?2900:2200)) {
            waterGuides.verify();
            waterSequence.verify();
            waterCamera.verify();
            waterDomain.verify();
            if(!VISUAL_ONLY && !WATER_COMPARE && !(CLEAN_WATER && WATER_MATRIX)) {
                if (reload==null || !reload.isDone()) throw new IllegalStateException("Resource reload incomplete");
                reload.join();
                if (!WATER_MATRIX && (!visitedNether || !returnedOverworld)) throw new IllegalStateException("Dimension round trip incomplete");
            }
            if (!RtComposite.enabled() || RtComposite.frameCounter()<100) throw new IllegalStateException("RT frames missing");
            if(!WaterGuideCapture.ENABLED && System.getProperty("gysf.smoke.waveStrength")!=null && (!CausticaConfig.Rt.Composite.WATER_WAVES.value()
                    || CausticaConfig.Rt.Composite.WATER_WAVE_STRENGTH.value()!=Float.parseFloat(System.getProperty("gysf.smoke.waveStrength"))))
                throw new IllegalStateException("Requested smoke wave strength was not retained");
            if(System.getProperty("gysf.smoke.waveSpeed")!=null && (!WATER_COMPARE || WaterSequenceCapture.ENABLED)
                    && (!CausticaConfig.Rt.Composite.WATER_WAVES.value()
                    || CausticaConfig.Rt.Composite.WATER_WAVE_SPEED.value()!=requestedWaveSpeed()))
                throw new IllegalStateException("Requested smoke wave speed was not retained");
            if (Boolean.getBoolean("gysf.smoke.vooxxy") && (!VoxyCompat.enabled() || maxMeshes==0 || maxRtSections==0)) throw new IllegalStateException("External ray-traced LOD geometry missing");
            long textured=dev.comfyfluffy.caustica.rt.terrain.RtDistantHorizonsTerrain.INSTANCE.texturedBuildCount();
            long leafCutouts=dev.comfyfluffy.caustica.rt.terrain.RtDistantHorizonsTerrain.INSTANCE.leafCutoutQuadCount();
            var lod=dev.comfyfluffy.caustica.rt.terrain.RtDistantHorizonsTerrain.INSTANCE;
            if(dev.comfyfluffy.caustica.rt.terrain.RtWaterGeometry.runtimeEnabled()) {
                int packed=dev.comfyfluffy.caustica.rt.accel.RtAccel.peakWaterInstanceCount();
                if(maxNearWaterInstances==0 || maxLodWaterInstances==0 || packed<2)
                    throw new IllegalStateException("Finite water was not published in both near and LOD TLAS inputs");
                System.out.println("GYSF_FINITE_WATER: nearChildren="+maxNearWaterInstances+" lodChildren="+maxLodWaterInstances+" packedPeak="+packed);
                if(dev.comfyfluffy.caustica.compat.VoxyWaterDomain.supported()) {
                    var fields=RtComposite.INSTANCE.waterFieldStatistics();
                    if(fields.uploads()==0||fields.knownNodes()==0||fields.available()==0||fields.liveBytes()==0)
                        throw new IllegalStateException("Canonical water field did not reach GPU residency: "+fields);
                    System.out.println("GYSF_WATER_FIELD: PASS "+fields);
                }
            }
            if(WATER_FIXTURE && lod.waterQuadCount()==0) throw new IllegalStateException("Textured LOD water was not converted");
            if(Boolean.getBoolean("gysf.smoke.requirePreciseFluids") && lod.preciseFluidQuadCount()==0)
                throw new IllegalStateException("API 6 precise fluid geometry was not converted");
            if(Boolean.getBoolean("gysf.smoke.expectLegacyFluids") && lod.preciseFluidQuadCount()!=0)
                throw new IllegalStateException("Legacy compatibility run unexpectedly consumed API 6 heights");
            if(MEMORY_PRESSURE && (maxMemoryDeferrals==0 || lod.memoryReductionCount()==0 || !observedBudgetRecovery
                    || pressureFallbackFrames<20 || !observedReducedProvider))
                throw new IllegalStateException("LOD memory degradation/recovery was not observed before resource reload");
            if(Boolean.getBoolean("gysf.smoke.requireDriverBudget") && !sawDriverBudget)
                throw new IllegalStateException("Driver memory budget extension was not active");
            if(Boolean.getBoolean("gysf.smoke.noMemoryDeferrals") && maxMemoryDeferrals!=0)
                throw new IllegalStateException("Unexpected LOD memory deferral in baseline run");
            if(MOTION && (lod.voxyReplacementSwapCount()==0 || lod.voxyCoverageCheckCount()==0))
                throw new IllegalStateException("Voxy coverage validation or replacement handoffs missing");
            if(MOTION && (motionMinX>-300 || motionMaxX<400 || motionMinY>100 || motionMaxY<170))
                throw new IllegalStateException("Scripted camera route was not observed");
            if(WATER_DOMAIN_MOTION && (!MOTION || terrainMinX>-3800 || terrainMaxX<3800))
                throw new IllegalStateException("Positive and negative water-domain terrain rebases were not observed");
            if (Boolean.getBoolean("gysf.smoke.vooxxy") && !Boolean.getBoolean("gysf.voxy.colourProxy")
                    && (textured==0 || VoxyCompat.texturedMeshCount()==0 || VoxyCompat.boundAtlasSlot()==0))
                throw new IllegalStateException("Textured Voxy geometry or atlas binding missing");
            if(TEXTURE_FIXTURE && !Boolean.getBoolean("gysf.voxy.colourProxy") && leafCutouts==0)
                throw new IllegalStateException("Foliage alpha-cutout metadata missing");
            System.out.println("GYSF_SMOKE: PASS frames="+RtComposite.frameCounter()+" maxVoxyMeshes="+maxMeshes+" maxRtLodSections="+maxRtSections+" texturedBatches="+textured+" atlasSlot="+VoxyCompat.boundAtlasSlot()+" leafCutoutQuads="+leafCutouts
                    +" atomicSwaps="+lod.atomicVoxySwapCount()+" replacementSwaps="+lod.voxyReplacementSwapCount()+" coverageChecks="+lod.voxyCoverageCheckCount()+" waterQuads="+lod.waterQuadCount()+" preciseFluidQuads="+lod.preciseFluidQuadCount());
            if(MOTION) System.out.println("GYSF_SMOKE: observed motion x="+motionMinX+".."+motionMaxX+" y="+motionMinY+".."+motionMaxY);
            if(WATER_DOMAIN_MOTION) System.out.println("GYSF_SMOKE: observed terrain origin x="+terrainMinX+".."+terrainMaxX);
            if(memoryContext!=null) System.out.println("GYSF_SMOKE: memory live="+memoryContext.lodMemory().liveBytes()
                    +" peak="+memoryContext.lodMemory().peakBytes()+" deferrals="+maxMemoryDeferrals
                    +" reductions="+lod.memoryReductionCount()+" recoveries="+lod.memoryRecoveryCount()
                    +" recoveredBeforeReload="+observedBudgetRecovery+" driverBudget="+sawDriverBudget
                    +" pressureFallbackFrames="+pressureFallbackFrames+" reducedProvider="+observedReducedProvider);
            finished=true;
            if(CLEAN_WATER) client.getSingleplayerServer().submit(()->client.getSingleplayerServer().tickRateManager().setFrozen(false));
            client.disconnectWithSavingScreen();
        }
    }

    private static float requestedWaveSpeed() {
        float speed=Float.parseFloat(System.getProperty("gysf.smoke.waveSpeed","1.0"));
        if(!Float.isFinite(speed) || speed<0 || speed>4) throw new IllegalStateException("Invalid smoke wave speed");
        return speed;
    }

    private void waterCapture(Minecraft client,String name) {
        System.out.println("GYSF_WATER_MATRIX: "+name+" waves="+CausticaConfig.Rt.Composite.WATER_WAVES.value()
                +" bounces="+CausticaConfig.Rt.Composite.MAX_BOUNCES.value()+" denoiser="+CausticaConfig.Rt.Composite.DENOISER.value()
                +" hidden="+Boolean.getBoolean("gysf.voxy.hideTextured"));
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image->{
            try {Files.createDirectories(Path.of("screenshots"));image.writeToFile(Path.of("screenshots","gysf-water-"+name+".png"));}
            catch(Exception e){throw new RuntimeException(e);}finally{image.close();}
        });
    }
}
