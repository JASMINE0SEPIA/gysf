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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Fixed-eye, static-field numerical comparison. Snapshot I/O is not a performance measurement. */
public final class WaterGuideCapture {
    public static final boolean ENABLED=Boolean.getBoolean("gysf.smoke.waterGuides");
    public static final boolean OPTICS=Boolean.getBoolean("gysf.smoke.waterOptics");
    public static final boolean VARIED=Boolean.getBoolean("gysf.smoke.waterVaried");
    public static final int END_TICK=OPTICS?3500:2450;
    private static final String[] NAMES=OPTICS?new String[]{"under-raw4-1","under-raw8-1","under-rr4-1","under-rr8-1",
            "under-raw4-4","under-raw8-4","under-rr4-4","under-rr8-4"}:new String[]{"above-1","above-4","under-1","under-4"};
    private static int phase=-1;
    private int phaseStart,completed;
    private long quietSince,sceneToken=Long.MIN_VALUE;
    private long phaseFrame,requestFrame;
    private CompletableFuture<Path> capture;
    private final List<CompletableFuture<?>> screenshots=new ArrayList<>();
    private CompletableFuture<?> fixture;
    public static boolean locked(){return ENABLED&&phase>=0&&phase<NAMES.length&&Minecraft.getInstance().player!=null;}
    private static boolean above(){return !OPTICS&&phase<2;}
    private static double eyeY(){return OPTICS?111.62:above()?141.62:VARIED?109.5:110.25;}
    private static int pitch(){return above()?16:-2;}
    private static BlockState variedState(int x,int y,int z) {
        int top=z>=80?112:111;
        if(x>=54&&x<=59&&z>=60&&z<=67&&y==112)return Blocks.STONE.defaultBlockState();
        if(z>=88&&z<=91&&y==115)return Blocks.GLASS.defaultBlockState();
        if(x>=68&&x<=73&&z>=116&&z<=123&&y==113)return Blocks.GLASS.defaultBlockState();
        if(y<top)return Blocks.WATER.defaultBlockState();
        if(y==top) {
            int level=(Math.floorDiv(x-48,4)+Math.floorDiv(z-16,8))&7;
            // Preserve the separate still-height capability probe at 64,111,64.
            if(x>=63&&x<=65&&z>=63&&z<=65)level=0;
            return Blocks.WATER.defaultBlockState().setValue(BlockStateProperties.LEVEL,level);
        }
        return Blocks.AIR.defaultBlockState();
    }
    void prepareFixture(Minecraft client,int tick) {
        if(!VARIED)return;
        if(tick==350) {
            MinecraftServer server=client.getSingleplayerServer();
            fixture=server.submit(()->{
                server.tickRateManager().setFrozen(true);
                var pos=new BlockPos.MutableBlockPos();
                for(int x=48;x<=79;x++)for(int z=16;z<=144;z++)for(int y=111;y<=115;y++)
                    server.overworld().setBlock(pos.set(x,y,z),variedState(x,y,z),2);
                System.out.println("GYSF_VARIED_WATER: placed frozen flow levels, step and stone/glass covers");
            });
        }
    }
    static boolean verifyFixture(ServerLevel level) {
        var pos=new BlockPos.MutableBlockPos();
        for(int x=48;x<=79;x++)for(int z=16;z<=144;z++)for(int y=111;y<=143;y++) {
            BlockState expected=y<=115?variedState(x,y,z):Blocks.AIR.defaultBlockState();
            if(!level.getBlockState(pos.set(x,y,z)).equals(expected))return false;
        }
        return true;
    }
    public static void enforceCamera() {
        if(!locked())return;
        var client=Minecraft.getInstance();
        if(client.gui.screen()!=null)client.gui.setScreen(null);
        net.minecraft.client.KeyMapping.releaseAll();
        client.player.setPos(64.5,eyeY()-client.player.getEyeHeight(),above()?.5:32.5);
        client.player.setDeltaMovement(Vec3.ZERO);
        client.player.setYRot(0);client.player.setXRot(pitch());
        client.player.yRotO=0;client.player.xRotO=pitch();
    }
    void sample(Minecraft client,int tick) {
        if(!ENABLED)return;
        if(tick==600)begin(client,tick,0);
        if(!locked())return;
        enforceCamera();
        // Only the recorded raw reference frame needs 32 spp. Keep scene publication and the
        // asynchronous readback wait at 1 spp; never reset it until a render has consumed the request.
        if(OPTICS&&capture!=null&&RtComposite.frameCounter()>requestFrame)CausticaConfig.Rt.Composite.SPP.set(1);
        if(capture!=null&&capture.isDone()) {
            Path directory=capture.join();
            var screenshot=new CompletableFuture<Void>();screenshots.add(screenshot);
            Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(),image->{
                try{image.writeToFile(directory.resolve("display.png"));screenshot.complete(null);}
                catch(Exception e){screenshot.completeExceptionally(e);}finally{image.close();}
            });
            completed++;capture=null;
            if(completed==NAMES.length){phase=NAMES.length;System.out.println("GYSF_WATER_GUIDES: captures complete");return;}
            begin(client,tick,phase+1);
        }
        if(tick<930||tick-phaseStart<100||RtComposite.frameCounter()-phaseFrame<64||capture!=null)return;
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
                ||client.player.getYRot()!=0||client.player.getXRot()!=pitch())
            throw new IllegalStateException("Guide camera is not locked");
        Path root=Path.of(Objects.requireNonNull(System.getProperty("gysf.smoke.guideDir"),"Missing guideDir"));
        var context=new LinkedHashMap<String,Object>();
        context.put("phase",NAMES[phase]);context.put("nearReference",reference);context.put("nearReady",near);context.put("farReady",far);
        context.put("expectedEye",List.of(64.5,eyeY(),above()?.5:32.5));context.put("expectedRotation",List.of(0,pitch()));
        context.put("renderDistance",client.options.renderDistance().get());context.put("optics",OPTICS);
        context.put("varied",VARIED);
        context.put("particles",CausticaConfig.Rt.Entities.PARTICLES_ENABLED.value());
        if(OPTICS)CausticaConfig.Rt.Composite.SPP.set((phase&3)<2?32:1);
        context.put("spp",CausticaConfig.Rt.Composite.SPP.value());context.put("bounces",CausticaConfig.Rt.Composite.MAX_BOUNCES.value());
        context.put("rr",CausticaConfig.Rt.DlssRr.ENABLED.value());
        requestFrame=RtComposite.frameCounter();
        capture=RtGuideCapture.INSTANCE.request(root.resolve(NAMES[phase]),context);
        System.out.println("GYSF_WATER_GUIDES: requested="+NAMES[phase]+" reference="+reference);
    }
    private void begin(Minecraft client,int tick,int nextPhase) {
        phase=nextPhase;phaseStart=tick;quietSince=0;sceneToken=Long.MIN_VALUE;
        phaseFrame=RtComposite.frameCounter();
        CausticaConfig.Rt.Composite.WATER_WAVES.set(true);
        CausticaConfig.Rt.Composite.WATER_WAVE_STRENGTH.set(OPTICS?(phase<4?1f:4f):(phase&1)==0?1f:4f);
        CausticaConfig.Rt.Composite.WATER_WAVE_SPEED.set(0f);
        if(OPTICS) {
            boolean rr=(phase&3)>=2;
            CausticaConfig.Rt.DlssRr.ENABLED.set(rr);CausticaConfig.Rt.DlssRr.QUALITY.set(5);
            CausticaConfig.Rt.Composite.DENOISER.set(rr);CausticaConfig.Rt.Nrd.ENABLED.set(false);
            CausticaConfig.Rt.Denoise.ENABLED.set(false);
            CausticaConfig.Rt.Fsr.ENABLED.set(false);CausticaConfig.Rt.Xess.ENABLED.set(false);CausticaConfig.Rt.Fg.ENABLED.set(false);
            CausticaConfig.Rt.Composite.SPP.set(1);CausticaConfig.Rt.Composite.MAX_BOUNCES.set((phase&1)==0?4:8);
            client.options.fov().set(70);
        }
        client.options.bobView().set(false);
        enforceCamera();
        client.player.connection.sendCommand(String.format(Locale.ROOT,"execute in minecraft:overworld run tp @s %.9f %.9f %.9f 0 %d",
                client.player.getX(),client.player.getY(),client.player.getZ(),pitch()));
        System.out.println("GYSF_WATER_GUIDES: phase="+NAMES[phase]+" eye="+client.player.getEyePosition());
    }
    void verify() {
        if(!ENABLED)return;
        if(VARIED&&(fixture==null||!fixture.isDone()))throw new IllegalStateException("Varied fixture incomplete");
        if(VARIED)fixture.join();
        if(completed!=NAMES.length||capture!=null)throw new IllegalStateException("Guide phases incomplete: "+completed);
        for(var screenshot:screenshots) {
            if(!screenshot.isDone())throw new IllegalStateException("Guide display snapshot did not complete before the deadline");
            screenshot.join();
        }
        System.out.println("GYSF_WATER_GUIDES: PASS phases="+NAMES.length+" locked=true jitter=0 staticWaves=true");
    }
}
