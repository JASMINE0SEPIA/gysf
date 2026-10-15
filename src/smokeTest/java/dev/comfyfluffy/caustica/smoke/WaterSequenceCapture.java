package dev.comfyfluffy.caustica.smoke;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import java.nio.file.*;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded image/metadata sampling; capture overhead is not a game-performance benchmark. */
final class WaterSequenceCapture {
    static final boolean ENABLED=Boolean.getBoolean("gysf.smoke.waterSequence");
    private Path directory;
    private long previousFrame=-1;
    private int requested,duplicateFrames;
    private final AtomicInteger saved=new AtomicInteger();

    void sample(Minecraft client,int tick) {
        if(!ENABLED || tick<1320 || tick>1560) return;
        long frame=RtComposite.frameCounter();
        if(frame==previousFrame) { duplicateFrames++; return; }
        previousFrame=frame;
        if(!CausticaConfig.Rt.Composite.WATER_WAVES.value() || CausticaConfig.Rt.Composite.WATER_WAVE_SPEED.value()<=0)
            throw new IllegalStateException("Water sequence animation is not active");
        try {
            if(directory==null) {
                directory=Path.of("screenshots","clean-water-sequence-"+System.currentTimeMillis());
                Files.createDirectory(directory);
                Files.writeString(directory.resolve("frames.csv"),"tick,rt_frame,request_ns,camera_x,camera_y,camera_z,near_ready,far_ready,wave_strength,wave_speed,file\n");
                System.out.println("GYSF_WATER_SEQUENCE: dir="+directory+" nearReference="+Boolean.getBoolean("gysf.smoke.waterNearReference"));
            }
            boolean near=RtTerrain.isSectionReady(new BlockPos(64,110,32));
            boolean far=RtTerrain.isSectionReady(new BlockPos(64,110,128));
            if(!near || far!=Boolean.getBoolean("gysf.smoke.waterNearReference"))
                throw new IllegalStateException("Water sequence ownership changed");
            String name=String.format(Locale.ROOT,"frame-%04d.png",tick);
            String row=String.format(Locale.ROOT,"%d,%d,%d,%.9f,%.9f,%.9f,%s,%s,%.9g,%.9g,%s%n",tick,frame,System.nanoTime(),
                    client.player.getX(),client.player.getY(),client.player.getZ(),near,far,
                    CausticaConfig.Rt.Composite.WATER_WAVE_STRENGTH.value(),CausticaConfig.Rt.Composite.WATER_WAVE_SPEED.value(),name);
            Path destination=directory;
            requested++;
            Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(),image->{
                try {
                    image.writeToFile(destination.resolve(name));
                    synchronized(this) {
                        Files.writeString(destination.resolve("frames.csv"),row,StandardOpenOption.APPEND);
                        saved.incrementAndGet();
                    }
                } catch(Exception e) { throw new RuntimeException(e); }
                finally { image.close(); }
            });
        } catch(java.io.IOException e) { throw new RuntimeException(e); }
    }

    void verify() {
        if(!ENABLED) return;
        if(saved.get()!=requested || saved.get()<120) throw new IllegalStateException("Water sequence incomplete: "+saved.get()+"/"+requested);
        System.out.println("GYSF_WATER_SEQUENCE: saved="+saved.get()+" duplicateRenderFrames="+duplicateFrames+" dir="+directory);
    }
}
