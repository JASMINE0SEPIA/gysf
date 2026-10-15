package dev.comfyfluffy.caustica.smoke;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.rt.RtWaterCameraProbe;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import java.nio.file.*;
import java.util.Locale;

/** Isolated real-geometry medium checks. The two flat controls deliberately supply a wrong CPU hint. */
public final class WaterCameraCapture {
    public static final boolean ENABLED=Boolean.getBoolean("gysf.smoke.waterCamera");
    private static final int[] START={900,1090,1280,1520,1760,1950};
    private static final String[] NAME={"flat-below","flat-above","waves-below","wave-crossing","projection-bob"};
    private static double eyeY=Double.NaN;
    private static float projectionBobY;
    private RtWaterCameraProbe.Stats baseline;
    private int passed;

    public static boolean locked() { return ENABLED && Double.isFinite(eyeY) && Minecraft.getInstance().player!=null; }
    public static float projectionBobY() { return projectionBobY; }
    public static void enforceCamera() {
        if(!locked())return;
        var client=Minecraft.getInstance();
        if(client.gui.screen()!=null)client.gui.setScreen(null);
        net.minecraft.client.KeyMapping.releaseAll();
        client.player.setPos(64.5,eyeY-client.player.getEyeHeight(),32.5);
        client.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        client.player.setYRot(0);client.player.setXRot(-2);
        client.player.yRotO=0;client.player.xRotO=-2;
    }

    void sample(Minecraft client,int tick) {
        if(!ENABLED || tick<START[0] || tick>=START[5])return;
        int phase=0;while(tick>=START[phase+1])phase++;
        if(tick==START[phase]) {
            // The near mesher emits an open source block's exact 8/9 height, not a full block.
            // The authored exponential spectrum is nonnegative; a trough need not be below the rest
            // plane. Place the final eye in the spectrum's interior, not 3 cm above its minimum bound.
            eyeY=111.0+8.0/9.0+(phase==3?0.20:phase==0||phase==2?-0.03:0.03);
            projectionBobY=phase==4?0.08f:0;
            CausticaConfig.Rt.Composite.WATER_WAVES.set(phase==2||phase==3);
            CausticaConfig.Rt.Composite.WATER_WAVE_SPEED.set(phase==2||phase==3?1f:0f);
            System.setProperty("gysf.rt.testInvertCameraHint",Boolean.toString(phase<2));
            enforceCamera();
            client.player.connection.sendCommand(String.format(Locale.ROOT,"tp @s 64.5 %.9f 32.5 0 -2",client.player.getY()));
            System.out.println("GYSF_WATER_CAMERA: begin="+NAME[phase]+" eyeY="+eyeY+" invertedHint="+(phase<2)+" projectionBobY="+projectionBobY);
        }
        enforceCamera();
        if(tick==START[phase]+30)baseline=RtWaterCameraProbe.stats(); // discard ring/camera transition frames
        if(tick==START[phase+1]-1) {
            var end=RtWaterCameraProbe.stats();
            long resolved=end.resolved()-baseline.resolved(),wet=end.toWater()-baseline.toWater(),dry=end.toAir()-baseline.toAir();
            long wetSamples=end.wet()-baseline.wet(),drySamples=end.dry()-baseline.dry();
            boolean correct=switch(phase) {
                case 0 -> wet==resolved && wetSamples==resolved;
                case 1 -> dry==resolved && drySamples==resolved;
                case 2 -> wetSamples==resolved;
                case 3 -> wetSamples>=10 && drySamples>=10 && wet>=10;
                default -> wetSamples==resolved && wet==resolved;
            };
            if(resolved<100 || end.disagreements()!=baseline.disagreements()
                    || !correct)
                throw new IllegalStateException("Water camera phase failed: "+NAME[phase]+" resolved="+resolved+" toWater="+wet+" toAir="+dry+" stats="+end);
            System.out.println("GYSF_WATER_CAMERA: PASS "+NAME[phase]+" resolved="+resolved+" toWater="+wet+" toAir="+dry
                    +" wet="+wetSamples+" dry="+drySamples
                    +" disagreements="+(end.disagreements()-baseline.disagreements())+" camera="+client.player.getEyePosition());
            String name="gysf-camera-"+NAME[phase]+".png";
            Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(),image->{
                try {Files.createDirectories(Path.of("screenshots"));image.writeToFile(Path.of("screenshots",name));}
                catch(Exception e){throw new RuntimeException(e);}finally{image.close();}
            });
            passed++;
        }
    }
    void verify() {
        if(!ENABLED)return;
        System.clearProperty("gysf.rt.testInvertCameraHint");
        if(passed!=5)throw new IllegalStateException("Water camera phases incomplete: "+passed);
        CausticaConfig.Rt.Composite.WATER_WAVES.set(true);
        CausticaConfig.Rt.Composite.WATER_WAVE_SPEED.set(1f);
        System.out.println("GYSF_WATER_CAMERA: PASS all phases "+RtWaterCameraProbe.stats());
    }
}
