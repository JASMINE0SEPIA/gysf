package dev.comfyfluffy.caustica.rt.terrain;

import java.nio.*;
import java.nio.file.*;

/** Production Java packing for two coincident fine/coarse edges, including both quad diagonals. */
public final class WaterLayerInputs {
    private static final int CASES=4096;
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Output file required");
        var bytes=ByteBuffer.allocate(CASES*2*RtWaterGeometry.PRIMITIVE_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        var values=bytes.asFloatBuffer();
        for(int c=0;c<CASES;c++) {
            int anchorY=((c/90)%3-1)*128;
            float rest=175f+8f/9f-anchorY,size=1<<((c/2)%5),high=176.25f-anchorY;
            int[] indices=(c&2)==0?new int[]{0,1,2,0,2,3}:new int[]{0,1,3,1,2,3};
            var a=RtWaterGeometry.pack(quad(31,16,1,rest,rest),indices,0,2,null);
            var b=RtWaterGeometry.pack(quad(32,16,size,rest,high),indices,0,2,null);
            if(a.topTriangles()!=2||b.topTriangles()!=2)throw new IllegalStateException("Fixture must contain free water tops");
            values.put(a.primitives(),RtWaterGeometry.PRIMITIVE_FLOATS,RtWaterGeometry.PRIMITIVE_FLOATS);
            values.put(b.primitives(),0,RtWaterGeometry.PRIMITIVE_FLOATS);
        }
        Files.write(Path.of(args[0]),bytes.array());
        System.out.println("WATER_LAYER_INPUTS cases="+CASES+" bytes="+bytes.capacity()+" diagonals=2 scales=1,2,4,8,16 yAnchors=-128,0,128");
    }
    private static float[] quad(float x,float z,float size,float leftY,float rightY) {
        return new float[]{x,leftY,z, x,leftY,z+size, x+size,rightY,z+size, x+size,rightY,z};
    }
}
