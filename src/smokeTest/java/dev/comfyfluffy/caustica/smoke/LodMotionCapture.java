package dev.comfyfluffy.caustica.smoke;

import dev.comfyfluffy.caustica.compat.VoxyCompat;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.terrain.RtDistantHorizonsTerrain;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;

/** Repeatable frame cadence during continuous camera/movement input in a physical save copy. */
public final class LodMotionCapture {
    public static boolean enabled() { return Boolean.getBoolean("gysf.smoke.lodMotion"); }
    private static LodMotionCapture active;
    private static final double SECONDS = Double.parseDouble(System.getProperty("gysf.smoke.motionSeconds", "20"));
    private final StringBuilder rows = new StringBuilder("phase,seconds,intervalMs,rtFrame,resident,pending,retargets\n");
    private final Map<String,Object> states = new LinkedHashMap<>();
    private int ticks,worldTicks,exitTicks,phase=-1;
    private long worldStart,phaseStart,lastFrame,lastRt;
    private boolean opened,finished,positioned;
    private double anchorX,anchorY,anchorZ;
    private float anchorYaw;
    private jdk.jfr.Recording recording;

    public LodMotionCapture() { active=this; }
    public static void camera() {
        if(!enabled() || active==null || active.phase<0 || active.phase>3)return;
        var player=Minecraft.getInstance().player;
        if(player==null)return;
        double seconds=(System.nanoTime()-active.phaseStart)/1e9;
        float yaw=active.anchorYaw+(active.phase==1||active.phase==3 ? (float)(seconds*45) : 0);
        player.setYRot(yaw);player.yRotO=yaw;
        player.setYHeadRot(yaw);player.yHeadRotO=yaw;
        player.setXRot(15);player.xRotO=15;
    }
    public static void frame() {
        if(!enabled() || active==null || active.phase<0 || active.phase>3)return;
        long now=System.nanoTime(),rt=RtComposite.frameCounter();
        long previous=active.lastFrame;active.lastFrame=now;
        if(previous==0 || rt==active.lastRt){active.lastRt=rt;return;}
        active.lastRt=rt;
        var lod=RtDistantHorizonsTerrain.INSTANCE;
        active.rows.append(active.phase).append(',').append((now-active.phaseStart)/1e9).append(',')
                .append((now-previous)/1e6).append(',').append(rt).append(',')
                .append(lod.residentSectionCount()).append(',').append(lod.hasPendingBuilds()).append(',')
                .append(lod.streamingStats().get("retargets")).append('\n');
    }
    void tick(Minecraft client) {
        if(++ticks>18000)throw new IllegalStateException("Motion replay timed out");
        if(finished){if(++exitTicks>40)client.stop();return;}
        if(!opened && client.gui.screen() instanceof TitleScreen && ticks>50) {
            if(!Files.isRegularFile(Path.of("saves/gysf-motion/level.dat")))
                throw new IllegalStateException("Motion replay requires a physical save copy");
            opened=true;client.options.pauseOnLostFocus=false;
            client.options.enableVsync().set(false);client.options.framerateLimit().set(60);
            client.options.renderDistance().set(6);client.options.simulationDistance().set(6);
            client.options.fov().set(70);client.getWindow().setWindowed(1600,1000);
            client.createWorldOpenFlows().openWorld("gysf-motion",()->{throw new IllegalStateException("Open cancelled");});
        }
        if(client.level==null || client.player==null)return;
        if(RtComposite.INSTANCE.hasFailed())throw new IllegalStateException("Motion replay RT failed");
        if(worldStart==0)worldStart=System.nanoTime();
        ++worldTicks;
        if(worldTicks==60) {
            anchorX=client.player.getX();anchorZ=client.player.getZ();anchorY=Math.max(130,client.player.getY()+16);
            anchorYaw=client.player.getYRot();
            if(Boolean.getBoolean("gysf.smoke.motionGreenfield")){anchorX=178.156;anchorY=130;anchorZ=-284.910;anchorYaw=35;}
            client.player.connection.sendCommand("gamemode spectator");
            client.player.connection.sendCommand("weather clear 1000000");
            client.player.connection.sendCommand("time set 6000");
            client.player.connection.sendCommand(String.format(Locale.ROOT,"tp @s %.4f %.4f %.4f %.3f 15",anchorX,anchorY,anchorZ,anchorYaw));
            positioned=true;
        }
        var lod=RtDistantHorizonsTerrain.INSTANCE;
        long now=System.nanoTime();
        if(phase<0 && positioned && worldTicks>900 && !lod.hasPendingBuilds() && lod.residentSectionCount()>0) {
            if(!client.player.isSpectator())throw new IllegalStateException("Motion replay setup commands did not enter spectator mode");
            if(Boolean.getBoolean("gysf.smoke.motionJfr"))startRecording();
            startPhase(now);
        } else if(phase>=0 && phase<=3) {
            double seconds=(now-phaseStart)/1e9;
            if(phase==2 || phase==3) {
                double angle=seconds*Math.PI/8;
                client.player.setPos(anchorX+32*Math.sin(angle),anchorY,anchorZ+32*(1-Math.cos(angle)));
                client.player.setDeltaMovement(0,0,0);
            }
            if(seconds>=SECONDS) {
                states.put("phase-"+phase,Map.of("streaming",lod.streamingStats(),"cache",VoxyCompat.snapshotCacheStats(),
                        "resident",lod.residentSectionCount(),"temporaryDistance",lod.memoryDistanceLimit()));
                startPhase(now);
            }
        } else if(phase==4 && !lod.hasPendingBuilds() && !VoxyCompat.refinementPending()) {
            if(lod.memoryDistanceLimit()!=0 || lod.streamingStats().get("viewCancellations")!=0)
                throw new IllegalStateException("Motion replay lost full range or cancelled camera work");
            states.put("final",Map.of("streaming",lod.streamingStats(),"resident",lod.residentSectionCount(),
                    "sources",VoxyCompat.texturedMeshCount(),"temporaryDistance",lod.memoryDistanceLimit(),
                    "ingest",VoxyCompat.ingestEnabled(),"rtFrames",RtComposite.frameCounter()));
            net.minecraft.client.Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(),image->{
                try {image.writeToFile(Path.of("motion-restored.png"));}
                catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}
                finally {image.close();}
            });
            try {
                Files.writeString(Path.of("motion-frames.csv"),rows);
                Files.writeString(Path.of("motion-results.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(states));
                if(recording!=null){recording.stop();recording.dump(Path.of("motion.jfr"));recording.close();}
            } catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}
            System.out.println("GYSF_PLAYER_VISUAL: PASS motion cadence recorded, streaming="+lod.streamingStats());
            client.disconnectWithSavingScreen();finished=true;
        }
        if((now-worldStart)/1e9>600)throw new IllegalStateException("Motion replay did not finish rebuilding");
    }
    private void startPhase(long now) {
        phase++;phaseStart=now;lastFrame=0;
        if(phase==4){var p=Minecraft.getInstance().player;p.setPos(anchorX,anchorY,anchorZ);}
        System.out.println("GYSF_MOTION: phase="+phase+" rtFrame="+RtComposite.frameCounter());
    }
    private void startRecording() {
        try {recording=new jdk.jfr.Recording(jdk.jfr.Configuration.getConfiguration("profile"));recording.start();}
        catch(java.io.IOException|java.text.ParseException failure){throw new IllegalStateException(failure);}
    }
}
