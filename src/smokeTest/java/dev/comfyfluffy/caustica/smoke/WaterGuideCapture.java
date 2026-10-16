package dev.comfyfluffy.caustica.smoke;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.RtGuideCapture;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrain;
import dev.comfyfluffy.caustica.rt.terrain.RtDistantHorizonsTerrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Fixed-eye, static-field numerical comparison. Snapshot I/O is not a performance measurement. */
public final class WaterGuideCapture {
    public static final boolean ENABLED=Boolean.getBoolean("gysf.smoke.waterGuides");
    public static final int END_TICK=2450;
    private static final String[] NAMES={"above-1","above-4","under-1","under-4"};
    private static int phase=-1;
    private int phaseStart,completed;
    private long quietSince,sceneToken=Long.MIN_VALUE;
    private CompletableFuture<Path> capture;
    private final List<CompletableFuture<?>> screenshots=new ArrayList<>();
    public static boolean locked(){return ENABLED&&phase>=0&&phase<4&&Minecraft.getInstance().player!=null;}
    private static double eyeY(){return phase<2?141.62:110.25;}
    public static void enforceCamera() {
        if(!locked())return;
        var client=Minecraft.getInstance();
        if(client.gui.screen()!=null)client.gui.setScreen(null);
        net.minecraft.client.KeyMapping.releaseAll();
        client.player.setPos(64.5,eyeY()-client.player.getEyeHeight(),.5+(phase>=2?32:0));
        client.player.setDeltaMovement(Vec3.ZERO);
        client.player.setYRot(0);client.player.setXRot(phase<2?16:-2);
        client.player.yRotO=0;client.player.xRotO=phase<2?16:-2;
    }
    void sample(Minecraft client,int tick) {
        if(!ENABLED)return;
        if(tick==600)begin(client,tick,0);
        if(!locked())return;
        enforceCamera();
        if(capture!=null&&capture.isDone()) {
            Path directory=capture.join();
            var screenshot=new CompletableFuture<Void>();screenshots.add(screenshot);
            Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(),image->{
                try{image.writeToFile(directory.resolve("display.png"));screenshot.complete(null);}
                catch(Exception e){screenshot.completeExceptionally(e);}finally{image.close();}
            });
            completed++;capture=null;
            if(completed==4){phase=4;System.out.println("GYSF_WATER_GUIDES: captures complete");return;}
            begin(client,tick,phase+1);
        }
        if(tick<930||tick-phaseStart<100||capture!=null)return;
        boolean reference=Boolean.getBoolean("gysf.smoke.waterNearReference");
        boolean near=RtTerrain.isSectionReady(new BlockPos(64,110,32));
        boolean far=RtTerrain.isSectionReady(new BlockPos(64,110,128));
        var terrain=RtTerrain.currentOrNull();var lod=RtDistantHorizonsTerrain.INSTANCE;
        var fields=RtComposite.INSTANCE.waterFieldStatistics();
        if(!near||far!=reference||terrain==null||terrain.hasPendingBuilds()||lod.hasPendingBuilds()
                ||fields.pending()!=0||fields.available()!=fields.requested()) {quietSince=0;return;}
        long token=lod.atomicVoxySwapCount()*31+lod.texturedBuildCount()*17+fields.uploads();
        long now=System.nanoTime();
        if(token!=sceneToken||quietSince==0){sceneToken=token;quietSince=now;return;}
        if(now-quietSince<2_000_000_000L)return;
        if(client.gui.screen()!=null||Math.abs(client.player.getEyeY()-eyeY())>1e-6
                ||client.player.getYRot()!=0||client.player.getXRot()!=(phase<2?16:-2))
            throw new IllegalStateException("Guide camera is not locked");
        Path root=Path.of(Objects.requireNonNull(System.getProperty("gysf.smoke.guideDir"),"Missing guideDir"));
        capture=RtGuideCapture.INSTANCE.request(root.resolve(NAMES[phase]),Map.of("phase",NAMES[phase],
                "nearReference",reference,"nearReady",near,"farReady",far,"expectedEye",List.of(64.5,eyeY(),phase<2?.5:32.5),
                "expectedRotation",List.of(0,phase<2?16:-2),"renderDistance",client.options.renderDistance().get()));
        System.out.println("GYSF_WATER_GUIDES: requested="+NAMES[phase]+" reference="+reference);
    }
    private void begin(Minecraft client,int tick,int nextPhase) {
        phase=nextPhase;phaseStart=tick;quietSince=0;sceneToken=Long.MIN_VALUE;
        CausticaConfig.Rt.Composite.WATER_WAVES.set(true);
        CausticaConfig.Rt.Composite.WATER_WAVE_STRENGTH.set((phase&1)==0?1f:4f);
        CausticaConfig.Rt.Composite.WATER_WAVE_SPEED.set(0f);
        client.options.bobView().set(false);
        enforceCamera();
        client.player.connection.sendCommand(String.format(Locale.ROOT,"execute in minecraft:overworld run tp @s %.9f %.9f %.9f 0 %d",
                client.player.getX(),client.player.getY(),client.player.getZ(),phase<2?16:-2));
        System.out.println("GYSF_WATER_GUIDES: phase="+NAMES[phase]+" eye="+client.player.getEyePosition());
    }
    void verify() {
        if(!ENABLED)return;
        if(completed!=4||capture!=null)throw new IllegalStateException("Guide phases incomplete: "+completed);
        for(var screenshot:screenshots) {
            if(!screenshot.isDone())throw new IllegalStateException("Guide display snapshot did not complete before the deadline");
            screenshot.join();
        }
        System.out.println("GYSF_WATER_GUIDES: PASS phases=4 locked=true jitter=0 staticWaves=true");
    }
}
