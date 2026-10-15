package dev.comfyfluffy.caustica.rt;

import java.io.*;
import java.nio.file.*;
import java.util.Locale;

/** CPU production transport -> Slang host consumer, without Minecraft or a Java math oracle. */
public final class WaterDomainFixtures {
    public static void main(String[] args) throws IOException {
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(Path.of(args[0])))) {
            int[] origins = {-29_999_872, -8192, -4096, -256, 0, 3840, 4096, 8192, 29_999_872};
            double[] times = {0,0.37,8,1023.875,3599.5,3600,86400,604800.125};
            for (int origin : origins) for (int si=0;si<3;si++) for (int vi=0;vi<3;vi++)
            for (double time : times) for (int fi=0;fi<3;fi++) for (int p=0;p<4;p++) for(int kind=0;kind<2;kind++) {
                float strength=si==0?0:si==1?1:4, speed=vi==0?0:vi==1?1:4;
                float footprint=fi==0?0:fi==1?2:16;
                for (int frame=0;frame<2;frame++) {
                    int shift=kind==0?frame:0;
                    int ox=origin+shift*512, oz=origin-shift*1024;
                    float px=p*3.25f-11.0f-shift*512, pz=p*5.5f-17.0f+shift*1024;
                    var clock=new RtWaterWaveClock().at(time);
                    double base=clock.baseSeconds()-(kind==1?frame*8:0);
                    float localTime=(float)(time-base);
                    // Time-control A is the continuous unwrapped reference, B the old hourly wrap.
                    float legacyTime=(float)(kind==1 && frame==0?time:time%3600);
                    out.printf(Locale.ROOT,"%d %d %.9g %.9g %.9g %.9g %.9g %.9g %.9g ",ox,oz,px,pz,localTime,footprint,strength,speed,legacyTime);
                    for (var w:RtWaterWaveDomain.create(ox,oz,base,speed)) out.printf(Locale.ROOT,"%.9g %.9g %.9g %.9g %.9g %.9g %.9g ",w.kx(),w.kz(),w.mx(),w.mz(),w.mainPhase(),w.meanderPhase(),w.omega());
                    out.println();
                }
            }
        }
    }
}
