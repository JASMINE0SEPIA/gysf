package dev.comfyfluffy.caustica.smoke;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.rt.*;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrain;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.concurrent.CompletableFuture;

/** Resource/race validation only. Deliberately changes physical waves and source epochs; no FPS claim. */
public final class WaterCacheLifecycle {
    public static final boolean ENABLED=Boolean.getBoolean("gysf.smoke.waterCacheLifecycle");
    private static final boolean PRESSURE=Boolean.getBoolean("gysf.smoke.waterCachePressure");
    private static final float[] STRENGTHS={4,0,2.5f,.25f,1.75f,1,4,.5f};
    private static int stage=-1,stageTick,changes,lastChangeTick;
    private static long lastChangeFrame,maxPending,maxReady,maxPeak,maxParentDeferrals,maxStale,maxCancelled,maxDeferrals;
    private static boolean increasedFallback,reloaded,nether,travelled,edited;
    private static double targetX=.5,targetY=140;
    private static CompletableFuture<Void> reload;
    private static CompletableFuture<?> edit;
    private static final BlockPos EDIT_POS=new BlockPos(8,128,8);
    private static BlockState savedBlock;
    private static long editPublished,editBuildToken,editDeferrals;

    public static boolean running(){return ENABLED&&stage>=0;}
    public static void enforceCamera(Minecraft client) {
        if(!running()||client.player==null)return;
        if(client.gui.screen()!=null)return;
        net.minecraft.client.KeyMapping.releaseAll();
        client.player.setPos(targetX,targetY,.5);client.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        client.player.setYRot(0);client.player.setXRot(20);client.player.yRotO=0;client.player.xRotO=20;
    }

    static boolean tick(Minecraft client,int tick) {
        if(tick<80)return false;
        if(stage<0) {
            if(!dev.comfyfluffy.caustica.rt.terrain.RtWaterGeometry.runtimeEnabled())throw new IllegalStateException("Finite water required");
            client.player.connection.sendCommand("gamemode spectator");teleport(client,"minecraft:overworld",.5,140);
            client.player.connection.sendCommand("time set noon");client.player.connection.sendCommand("weather clear");
            CausticaConfig.Rt.Fsr.ENABLED.set(false);CausticaConfig.Rt.Xess.ENABLED.set(false);CausticaConfig.Rt.Fg.ENABLED.set(false);
            CausticaConfig.Rt.Nrd.ENABLED.set(false);CausticaConfig.Rt.Denoise.ENABLED.set(true);
            CausticaConfig.Rt.Composite.DENOISER.set(true);CausticaConfig.Rt.DlssRr.ENABLED.set(true);CausticaConfig.Rt.DlssRr.PRESET.set(0);
            CausticaConfig.Rt.DlssRr.QUALITY.set(0);CausticaConfig.Rt.Composite.WATER_WAVES.set(true);
            CausticaConfig.Rt.Composite.WATER_WAVE_STRENGTH.set(1f);CausticaConfig.Rt.Composite.WATER_WAVE_SPEED.set(1f);
            CausticaConfig.Rt.Composite.WATER_WAVE_DETAIL.set(7);
            advance(0,tick,"warmup");
        }
        if(RtComposite.INSTANCE.hasFailed())throw new IllegalStateException("RT failed during water cache lifecycle");
        var ctx=RtContext.currentOrNull();if(ctx==null)return false;
        var stats=ctx.waterBoundsCache().statistics();
        if(!stats.enabled())throw new IllegalStateException("Runtime water bounds cache is disabled");
        maxPending=Math.max(maxPending,stats.pending());maxReady=Math.max(maxReady,stats.ready());maxPeak=Math.max(maxPeak,stats.peakBytes());
        maxParentDeferrals=Math.max(maxParentDeferrals,ctx.lodMemory().deferralCount());
        maxStale=Math.max(maxStale,stats.stale());maxCancelled=Math.max(maxCancelled,stats.cancellations());maxDeferrals=Math.max(maxDeferrals,stats.nativeDeferrals());
        if(PRESSURE&&stats.liveBytes()>(1L<<20))throw new IllegalStateException("Optional cache quota was exceeded");
        if(tick-stageTick>3000)throw new IllegalStateException("Water cache lifecycle timed out in stage "+stage);
        int needed=PRESSURE?1:32;
        switch(stage) {
            case 0 -> {
                if(stats.ready()>=needed&&RtComposite.frameCounter()>100)advance(1,tick,"rapid height/toggle changes");
            }
            case 1 -> {
                long frame=RtComposite.frameCounter();
                if(frame>lastChangeFrame&&CausticaConfig.Rt.Composite.WATER_WAVES.value()
                        &&CausticaConfig.Rt.Composite.WATER_WAVE_STRENGTH.value()==4&&stats.fallbacks()>0)increasedFallback=true;
                if(frame>lastChangeFrame&&(stats.pending()>0||tick-lastChangeTick>=20)) {
                    if(changes==24) {
                        CausticaConfig.Rt.Composite.WATER_WAVES.set(true);CausticaConfig.Rt.Composite.WATER_WAVE_STRENGTH.set(1f);
                        reload=client.reloadResourcePacks();advance(2,tick,"resource reload with asynchronous work");
                    } else {
                        float strength=STRENGTHS[changes%STRENGTHS.length];
                        CausticaConfig.Rt.Composite.WATER_WAVES.set(changes%5!=4);
                        CausticaConfig.Rt.Composite.WATER_WAVE_STRENGTH.set(strength);
                        changes++;lastChangeFrame=frame;lastChangeTick=tick;
                        System.out.println("GYSF_CACHE_LIFECYCLE: change="+changes+" strength="+strength+" pending="+stats.pending()+" ready="+stats.ready());
                    }
                }
            }
            case 2 -> {
                if(reload.isDone()) {
                    reload.join();reloaded=true;
                    teleport(client,"minecraft:the_nether",.5,90);advance(3,tick,"Nether entry");
                }
            }
            case 3 -> {
                if(client.level.dimension().equals(Level.NETHER)&&tick-stageTick>60) {
                    nether=true;teleport(client,"minecraft:overworld",.5,140);advance(4,tick,"Overworld return");
                }
            }
            case 4 -> {
                if(client.level.dimension().equals(Level.OVERWORLD)&&stats.ready()>=needed&&tick-stageTick>40
                        &&stats.pending()==0&&!RtTerrain.currentOrNull().hasPendingBuilds()&&!dev.comfyfluffy.caustica.rt.terrain.RtDistantHorizonsTerrain.INSTANCE.hasPendingBuilds()) {
                    editPublished=stats.published();editBuildToken=nearBuildToken();editDeferrals=stats.nativeDeferrals();
                    edit=client.getSingleplayerServer().submit(()->{
                        var server=client.getSingleplayerServer();server.tickRateManager().setFrozen(true);
                        savedBlock=server.overworld().getBlockState(EDIT_POS);
                        server.overworld().setBlock(EDIT_POS,Blocks.WATER.defaultBlockState(),2);
                    });
                    advance(7,tick,"replace source geometry with water block");
                }
            }
            case 7 -> {
                if(edit.isDone()) {
                    edit.join();
                    if(client.level.getBlockState(EDIT_POS).is(Blocks.WATER)&&nearBuildToken()>editBuildToken
                            &&(stats.published()>editPublished||PRESSURE&&stats.nativeDeferrals()>editDeferrals)
                            &&!RtTerrain.currentOrNull().hasPendingBuilds()&&stats.pending()==0) {
                        editBuildToken=nearBuildToken();
                        edit=client.getSingleplayerServer().submit(()->client.getSingleplayerServer().overworld().setBlock(EDIT_POS,savedBlock,2));
                        advance(8,tick,"restore edited source geometry");
                    }
                }
            }
            case 8 -> {
                if(edit.isDone()) {
                    edit.join();
                    if(client.level.getBlockState(EDIT_POS).equals(savedBlock)&&nearBuildToken()>editBuildToken
                            &&!RtTerrain.currentOrNull().hasPendingBuilds()&&stats.pending()==0) {
                        edited=true;client.getSingleplayerServer().submit(()->client.getSingleplayerServer().tickRateManager().setFrozen(false));
                        teleport(client,"minecraft:overworld",512.5,140);advance(5,tick,"travel to new sources");
                    }
                }
            }
            case 5 -> {
                if(client.player.getX()>500&&stats.ready()>=needed&&tick-stageTick>80) {
                    travelled=true;
                    CausticaConfig.Rt.Composite.WATER_WAVE_STRENGTH.set(.25f);advance(6,tick,"shutdown with pending work");
                }
            }
            case 6 -> {
                if(stats.pending()>0||tick-stageTick>60) {
                    if(!increasedFallback||!reloaded||!nether||!travelled||!edited||maxPending==0||maxStale+maxCancelled==0)
                        throw new IllegalStateException("Required cache fallback/stale/lifecycle event was not exercised");
                    if(PRESSURE&&(maxDeferrals==0||maxParentDeferrals!=0))throw new IllegalStateException("Optional cache pressure was not isolated from terrain quota");
                    System.out.println("GYSF_CACHE_LIFECYCLE: PASS changes="+changes+" reload="+reloaded+" nether="+nether+" travel="+travelled+" edited="+edited
                            +" fallback="+increasedFallback+" maxPending="+maxPending+" maxReady="+maxReady+" peak="+maxPeak
                            +" stale="+maxStale+" cancelled="+maxCancelled+" cacheDeferrals="+maxDeferrals+" terrainDeferrals="+maxParentDeferrals+" pressure="+PRESSURE);
                    System.out.println("GYSF_SMOKE: PASS waterCacheLifecycle=true");return true;
                }
            }
            default -> throw new IllegalStateException("Invalid water cache lifecycle stage");
        }
        return false;
    }
    private static void teleport(Minecraft client,String dimension,double x,double y) {
        targetX=x;targetY=y;
        client.player.connection.sendCommand("execute in "+dimension+" run tp @s "+x+" "+y+" 0.5 0 20");
    }
    private static long nearBuildToken() {
        try {var field=RtTerrain.class.getDeclaredField("buildToken");field.setAccessible(true);return field.getLong(RtTerrain.currentOrNull());}
        catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }
    private static void advance(int next,int tick,String label) {
        stage=next;stageTick=tick;
        System.out.println("GYSF_CACHE_LIFECYCLE: stage="+next+" "+label);
    }
}
