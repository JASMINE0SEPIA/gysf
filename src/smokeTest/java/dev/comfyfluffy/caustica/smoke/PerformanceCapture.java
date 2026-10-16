package dev.comfyfluffy.caustica.smoke;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.compat.VoxyCompat;
import dev.comfyfluffy.caustica.rt.*;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssRr;
import dev.comfyfluffy.caustica.rt.terrain.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import java.nio.file.*;
import java.io.*;
import java.util.*;

/** Repeated fixed-camera comparison in the isolated smoke world; no player settings or saves used. */
public final class PerformanceCapture {
    private static final boolean ENABLED=Boolean.getBoolean("gysf.smoke.performance");
    private static final int[] PRESETS=Arrays.stream(System.getProperty("gysf.smoke.perfPresets","0,4,6,-1,6,4,0").split(","))
            .mapToInt(Integer::parseInt).toArray();
    private static final int[] QUALITIES=qualities();
    private static final long SECOND=1_000_000_000L;
    private static final long INITIAL=Long.getLong("gysf.smoke.perfInitialSeconds",180L)*SECOND;
    private static final long SETTLE=Long.getLong("gysf.smoke.perfSettleSeconds",15L)*SECOND;
    private static final long MEASURE=Long.getLong("gysf.smoke.perfMeasureSeconds",20L)*SECOND;
    private static final ArrayList<Double> intervals=new ArrayList<>();
    private static final StringBuilder rows=new StringBuilder();
    private static long preparedAt,phaseAt,lastFrame,lastRtFrame;
    private static int phase=-1;
    private static Path output;
    private static final boolean PRESSURE=Boolean.getBoolean("gysf.smoke.nearMemoryPressure");
    private static final NearPressureCapture pressure=new NearPressureCapture();
    private static boolean pressureDone;
    private static long quietSince,lastQuietLog;
    private static long lastLodRevision=-1,lastPacked=-1,lastSwaps=-1,lastNearToken=-1;
    private static int lastNearInstances=-1;
    private static String phaseScene;
    private static long lastWaterUploads=-1,lastWaterNodes=-1;
    private static int lastWaterPending=-1;

    private static int[] qualities() {
        String value=System.getProperty("gysf.smoke.perfQualities","0");
        int[] parsed=Arrays.stream(value.split(",")).mapToInt(Integer::parseInt).toArray();
        if(parsed.length!=1 && parsed.length!=PRESETS.length)throw new IllegalArgumentException("Quality count must be one or match presets");
        int[] result=new int[PRESETS.length];
        for(int i=0;i<result.length;i++) {
            result[i]=parsed[parsed.length==1?0:i];
            if(result[i]<0||result[i]>5)throw new IllegalArgumentException("Invalid NGX quality");
        }
        return result;
    }

    static boolean tick(Minecraft client,int tick) {
        if(RtComposite.INSTANCE.hasFailed())throw new IllegalStateException("Performance run RT compositor failed");
        if(tick==80) {
            if(CausticaConfig.Rt.Omm.ENABLED.value() && !RtDeviceBringup.ommEnabled())
                throw new IllegalStateException("Requested OMM is not active on this Vulkan device");
            String label=System.getProperty("gysf.smoke.perfLabel","comparison");
            if(!label.matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException("Invalid performance label");
            output=Path.of("performance-smoke",label);
            try { Files.createDirectories(output); }
            catch(IOException e){throw new UncheckedIOException(e);}
            if(Files.exists(output.resolve("frames.csv")))throw new IllegalStateException("Fresh performance label required");
            rows.append("phase,rr,preset,elapsedMs,intervalMs,rtFrame,processBytes,budgetBytes,rtAllocationBytes,lodBytes,providerBytes,nearRadius,nearInstances,lodSections,deferrals,cameraX,cameraY,cameraZ,yaw,pitch,screen,lodRevision,texturedBuilds,atomicSwaps,nearTable,nearBuildToken,quality,renderWidth,renderHeight,displayWidth,displayHeight,filter,spp,bounces,finiteWater,omm,nearPending,lodPending,waterUploads,waterKnownNodes,waterPending\n");
            // A non-RR control must not silently inherit another vendor filter/upscaler or FG.
            CausticaConfig.Rt.Fsr.ENABLED.set(false);CausticaConfig.Rt.Xess.ENABLED.set(false);
            CausticaConfig.Rt.Fg.ENABLED.set(false);CausticaConfig.Rt.Nrd.ENABLED.set(false);
            CausticaConfig.Rt.Denoise.ENABLED.set(true);
            CausticaConfig.Rt.FrameStats.ENABLED.set(Boolean.getBoolean("gysf.smoke.perfCpuStats"));
            client.player.connection.sendCommand("gamemode spectator");
            client.player.connection.sendCommand("tp @s 0.5 140 0.5 0 20");
            client.player.connection.sendCommand("time set noon");
            client.player.connection.sendCommand("weather clear");
            CausticaConfig.Rt.Composite.WATER_WAVE_SPEED.set(0f);
            preparedAt=System.nanoTime();
            System.out.println("GYSF_PERF: initial warmup; fixed camera and waves; spp="+CausticaConfig.Rt.Composite.SPP.value()
                    +" bounces="+CausticaConfig.Rt.Composite.MAX_BOUNCES.value()+" OMM="+CausticaConfig.Rt.Omm.ENABLED.value()+"; wall cadence includes present");
        }
        if(tick==400)client.getSingleplayerServer().submit(()->client.getSingleplayerServer().tickRateManager().setFrozen(true));
        if(preparedAt==0)return false;
        long now=System.nanoTime();
        if(MemoryDiagnostics.ONLY) {
            if(!MemoryDiagnostics.tick(now,preparedAt,output))return false;
            client.getSingleplayerServer().submit(()->client.getSingleplayerServer().tickRateManager().setFrozen(false));
            preparedAt=0;return true;
        }
        if(PRESSURE && !pressureDone) {
            if(now-preparedAt<INITIAL)return false;
            if(!pressure.tick(client,now,output))return false;
            pressureDone=true;
            if(Boolean.getBoolean("gysf.smoke.nearMemoryPressureOnly")) {
                client.getSingleplayerServer().submit(()->client.getSingleplayerServer().tickRateManager().setFrozen(false));
                preparedAt=0;
                return true;
            }
        }
        boolean sceneQuiet=sceneQuiet(now);
        if(phase<0 && now-preparedAt>INITIAL+300*SECOND)
            throw new IllegalStateException("Performance scene did not settle within five extra minutes; no comparison accepted");
        if(phase<0 && now-preparedAt>=INITIAL && !sceneQuiet && now-lastQuietLog>=10*SECOND) {
            lastQuietLog=now;
            System.out.println("GYSF_PERF: waiting for ten seconds without near builds or LOD pack/publication changes");
        }
        if(phase<0 && now-preparedAt>=INITIAL && sceneQuiet) {
            if(RtDistantHorizonsTerrain.INSTANCE.residentSectionCount()==0)throw new IllegalStateException("No published RT LOD at benchmark start");
            phase=0;start(now);
        } else if(phase>=0 && now-phaseAt>=SETTLE+MEASURE) {
            if(intervals.size()<60)throw new IllegalStateException("Too few measured frames: "+intervals.size());
            double[] sorted=intervals.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            double total=intervals.stream().mapToDouble(Double::doubleValue).sum();
            System.out.printf(Locale.ROOT,"GYSF_PERF: phase=%d preset=%d quality=%d frames=%d mean=%.3fms median=%.3fms p95=%.3fms p99=%.3fms fps=%.2f%n",
                    phase,PRESETS[phase],QUALITIES[phase],sorted.length,total/sorted.length,sorted[sorted.length/2],sorted[(int)(sorted.length*.95)],sorted[(int)(sorted.length*.99)],1000*sorted.length/total);
            final int capturedPhase=phase;
            Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(),image->{
                try { image.writeToFile(output.resolve("phase-"+capturedPhase+".png")); }
                catch(IOException e){throw new UncheckedIOException(e);}finally{image.close();}
            });
            try { Files.writeString(output.resolve("frames.csv"),rows); }
            catch(IOException e){throw new UncheckedIOException(e);}
            if(++phase==PRESETS.length) {
                client.getSingleplayerServer().submit(()->client.getSingleplayerServer().tickRateManager().setFrozen(false));
                if(!VoxyCompat.active() || RtComposite.frameCounter()<100)throw new IllegalStateException("Joint RT rendering missing");
                System.out.println("GYSF_PERF: PASS output="+output+" (cadence comparison, not visual acceptance)");
                phase=-2;preparedAt=0;return true;
            }
            start(now);
        }
        return false;
    }

    private static void start(long now) {
        int preset=PRESETS[phase];
        CausticaConfig.Rt.DlssRr.ENABLED.set(preset>=0);
        if(preset>=0)CausticaConfig.Rt.DlssRr.PRESET.set(preset);
        CausticaConfig.Rt.DlssRr.QUALITY.set(QUALITIES[phase]);
        CausticaConfig.Rt.Composite.DENOISER.set(true);
        phaseAt=now;lastFrame=0;intervals.clear();phaseScene=null;
        System.out.println("GYSF_PERF: begin phase="+phase+" preset="+preset+" quality="+QUALITIES[phase]+" settleSeconds="+SETTLE/SECOND+" measureSeconds="+MEASURE/SECOND);
    }

    public static void frame() {
        if(ENABLED && PRESSURE && !pressureDone)NearOwnershipSmokeProbe.frame();
        if(!ENABLED || phase<0 || phase>=PRESETS.length || preparedAt==0)return;
        long now=System.nanoTime(),rtFrame=RtComposite.frameCounter();
        long previous=lastFrame;lastFrame=now;
        if(previous==0 || rtFrame==lastRtFrame || previous-phaseAt<SETTLE || now-phaseAt>SETTLE+MEASURE) {lastRtFrame=rtFrame;return;}
        lastRtFrame=rtFrame;
        var ctx=RtContext.currentOrNull();var near=RtTerrain.currentOrNull();
        if(ctx==null || near==null)throw new IllegalStateException("Renderer disappeared during measurement");
        var client=Minecraft.getInstance();
        if(client.gui.screen()!=null || Math.abs(client.player.getX()-.5)>1e-3 || Math.abs(client.player.getY()-140)>1e-3
                || Math.abs(client.player.getZ()-.5)>1e-3 || Math.abs(client.player.getYRot())>1e-3 || Math.abs(client.player.getXRot()-20)>1e-3)
            throw new IllegalStateException("Fixed-camera performance scene was changed by input or a screen");
        if(PRESETS[phase]>=0 && !RtDlssRr.INSTANCE.isReady())throw new IllegalStateException("Requested RR was not active");
        var info=RtComposite.INSTANCE.renderInfo();
        if(info==null || info.frame()!=rtFrame)throw new IllegalStateException("Missing actual rendered-frame metadata");
        if(!info.filter().equals(PRESETS[phase]>=0?"dlss-rr":"svgf"))throw new IllegalStateException("Unexpected actual reconstruction filter: "+info.filter());
        if(near.hasPendingBuilds() || RtDistantHorizonsTerrain.INSTANCE.hasPendingBuilds())
            throw new IllegalStateException("Geometry work resumed during measured frames; comparison rejected");
        var water=RtComposite.INSTANCE.waterFieldStatistics();
        String scene=nearRadius(near)+":"+near.staticInstances().size()+":"+near.tableAddress()+":"+nearBuildToken(near)
                +":"+RtDistantHorizonsTerrain.INSTANCE.residentSectionCount()+":"+RtDistantHorizonsTerrain.INSTANCE.emissiveRevision()
                +":"+RtDistantHorizonsTerrain.INSTANCE.texturedBuildCount()+":"+RtDistantHorizonsTerrain.INSTANCE.atomicVoxySwapCount()
                +":"+water.uploads()+":"+water.knownNodes()+":"+water.pending();
        if(phaseScene==null)phaseScene=scene;
        else if(!phaseScene.equals(scene))throw new IllegalStateException("Published geometry changed during measured phase");
        double ms=(now-previous)/1e6;intervals.add(ms);
        var heap=ctx.memoryBudget();
        rows.append(phase).append(',').append(PRESETS[phase]>=0).append(',').append(PRESETS[phase]).append(',')
                .append((now-phaseAt)/1e6).append(',').append(ms).append(',').append(rtFrame).append(',')
                .append(heap.usageBytes()).append(',').append(heap.budgetBytes()).append(',').append(heap.rtAllocationBytes()).append(',')
                .append(ctx.lodMemory().liveBytes()).append(',').append(VoxyCompat.ownedGpuBytes()).append(',')
                .append(nearRadius(near)).append(',').append(near.staticInstances().size()).append(',')
                .append(RtDistantHorizonsTerrain.INSTANCE.residentSectionCount()).append(',').append(ctx.lodMemory().deferralCount()).append(',');
        var camera=client.gameRenderer.mainCamera().position();
        rows.append(camera.x).append(',').append(camera.y).append(',').append(camera.z).append(',')
                .append(client.player.getYRot()).append(',').append(client.player.getXRot()).append(",none,");
        var lod=RtDistantHorizonsTerrain.INSTANCE;
        rows.append(lod.emissiveRevision()).append(',').append(lod.texturedBuildCount()).append(',').append(lod.atomicVoxySwapCount()).append(',')
                .append(near.tableAddress()).append(',').append(nearBuildToken(near)).append(',').append(QUALITIES[phase]).append(',')
                .append(info.renderWidth()).append(',').append(info.renderHeight()).append(',').append(info.displayWidth()).append(',').append(info.displayHeight()).append(',')
                .append(info.filter()).append(',').append(CausticaConfig.Rt.Composite.SPP.value()).append(',').append(CausticaConfig.Rt.Composite.MAX_BOUNCES.value()).append(',')
                .append(RtWaterGeometry.runtimeEnabled()).append(',').append(RtDeviceBringup.ommEnabled()).append(",false,false,")
                .append(water.uploads()).append(',').append(water.knownNodes()).append(',').append(water.pending()).append('\n');
    }

    public static boolean locked() {
        return ENABLED && preparedAt!=0 && Minecraft.getInstance().level!=null && Minecraft.getInstance().player!=null;
    }

    public static void enforceCamera() {
        if(!locked())return;
        var client=Minecraft.getInstance();
        if(client.gui.screen()!=null)client.gui.setScreen(null);
        net.minecraft.client.KeyMapping.releaseAll();
        client.player.setPos(.5,140,.5);
        client.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        client.player.setYRot(0);client.player.setXRot(20);
        client.player.yRotO=0;client.player.xRotO=20;
    }

    static int nearRadius(RtTerrain near) {
        try {var field=RtTerrain.class.getDeclaredField("windowRadius");field.setAccessible(true);return field.getInt(near);}
        catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }

    private static long nearBuildToken(RtTerrain near) {
        try {var field=RtTerrain.class.getDeclaredField("buildToken");field.setAccessible(true);return field.getLong(near);}
        catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }

    private static boolean sceneQuiet(long now) {
        var near=RtTerrain.currentOrNull();
        if(near==null){quietSince=now;return false;}
        var lod=RtDistantHorizonsTerrain.INSTANCE;
        if(near.hasPendingBuilds() || lod.hasPendingBuilds()){quietSince=now;return false;}
        long revision=lod.emissiveRevision(),packed=lod.texturedBuildCount(),swaps=lod.atomicVoxySwapCount(),token=nearBuildToken(near);
        int instances=near.staticInstances().size();
        var water=RtComposite.INSTANCE.waterFieldStatistics();
        if(revision!=lastLodRevision || packed!=lastPacked || swaps!=lastSwaps || token!=lastNearToken || instances!=lastNearInstances
                ||water.uploads()!=lastWaterUploads||water.knownNodes()!=lastWaterNodes||water.pending()!=lastWaterPending) {
            quietSince=now;lastLodRevision=revision;lastPacked=packed;lastSwaps=swaps;lastNearToken=token;lastNearInstances=instances;
            lastWaterUploads=water.uploads();lastWaterNodes=water.knownNodes();lastWaterPending=water.pending();
        }
        return quietSince!=0 && now-quietSince>=10*SECOND;
    }
}
