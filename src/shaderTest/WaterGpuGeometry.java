package dev.comfyfluffy.caustica.rt.terrain;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Native traversal reads the production CPU packet, not a separately authored GPU fixture. */
public final class WaterGpuGeometry {
    public static void main(String[] args) throws IOException {
        float[] vertices=new float[4*12],weights=new float[16];int[] indices=new int[24];
        for(int sample=0;sample<4;sample++) {
            float x=sample*3-6,z=sample*5-9;
            float[] height=switch(sample) {
                case 1 -> new float[]{112,112,112.4f,112.4f};
                case 2 -> new float[]{112,112.3f,112.4f,111.8f};
                default -> new float[]{112,112,112,112};
            };
            float[] quad={x,height[0],z, x,height[1],z+1, x+1,height[2],z+1, x+1,height[3],z};
            System.arraycopy(quad,0,vertices,sample*12,12);
            // Fixture index 2*sample is the u>=v half; the next is u<=v.
            int start=sample*4;int[] pair={start,start+2,start+3,start,start+1,start+2};
            System.arraycopy(pair,0,indices,sample*6,6);
            float[] shore=switch(sample) {
                case 0 -> new float[]{1,1,1,1};case 1 -> new float[]{0,0,1,1};
                case 2 -> new float[]{0,1,1,0};default -> new float[]{0,0,0,0};
            };
            System.arraycopy(shore,0,weights,sample*4,4);
        }
        var geometry=RtWaterGeometry.pack(vertices,indices,0,8,weights);
        try(var out=new PrintWriter(Files.newBufferedWriter(Path.of(args[0])))) {
            for(float value:geometry.primitives())out.printf(Locale.ROOT,"%.9g%n",value);
        }
        try(var out=new PrintWriter(Files.newBufferedWriter(Path.of(args[1])))) {
            for(float value:geometry.aabbs())out.printf(Locale.ROOT,"%.9g%n",value);
        }
    }
}
