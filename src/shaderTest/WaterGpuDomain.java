package dev.comfyfluffy.caustica.rt;
import java.io.*;
import java.nio.file.*;
import java.util.Locale;

/** Export the same CPU coefficients supplied by the renderer, rather than recomputing them on GPU. */
public final class WaterGpuDomain {
    public static void main(String[] args) throws IOException {
        try(var out=new PrintWriter(Files.newBufferedWriter(Path.of(args[0])))) {
            for(var c:RtWaterWaveDomain.create(0,0,0,1))
                out.printf(Locale.ROOT,"%.9g %.9g %.9g %.9g %.9g %.9g %.9g 0%n",c.kx(),c.kz(),c.mx(),c.mz(),c.mainPhase(),c.meanderPhase(),c.omega());
        }
    }
}
