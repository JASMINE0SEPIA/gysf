package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.rt.terrain.RtWaterGeometry;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class RtWaterBoundsCacheTest {
    @Test void everySliderValueAndQuarterBoundaryFitsItsCacheBin() {
        for(int i=0;i<=40000;i++)check(i/10000f);
        for(int i=0;i<=16;i++) {
            float value=i*.25f;
            check(Math.nextDown(value));check(value);check(Math.nextUp(value));
        }
        for(float invalid:new float[]{Float.NaN,Float.NEGATIVE_INFINITY,Float.POSITIVE_INFINITY}) {
            assertEquals(RtWaterGeometry.MAX_DISPLACEMENT,RtWaterBoundsCache.targetHeight(invalid,true));
            assertEquals(0,RtWaterBoundsCache.targetHeight(invalid,false));
        }
    }
    private static void check(float strength) {
        float required=RtWaterBoundsCache.requiredHeight(strength,true),target=RtWaterBoundsCache.targetHeight(strength,true);
        assertTrue(target>=required,"cache must enclose requested wave height: "+strength);
        assertTrue(target>=0&&target<=RtWaterGeometry.MAX_DISPLACEMENT);
        assertEquals(0,RtWaterBoundsCache.requiredHeight(strength,false));
        assertEquals(0,RtWaterBoundsCache.targetHeight(strength,false));
    }

    @Test void derivedBoundsContainSlopedSurfacesAcrossSignedFloatScales() {
        int[] indices={0,1,2,0,2,3};
        for(float origin:new float[]{-65536,-128,0,128,65536}) {
            float[] vertices={origin,origin+2,origin, origin,origin+2.2f,origin+1,
                    origin+1,origin+2.5f,origin+1, origin+1,origin+2.1f,origin};
            var packed=RtWaterGeometry.pack(vertices,indices,0,2,null);
            float[] original=packed.aabbs().clone();
            var input=RtWaterGeometry.boundsInput(packed);
            assertEquals(2,input.count());
            assertEquals(56,input.bytes());
            for(float cap:new float[]{0,.1375f,.55f,1.1f,RtWaterGeometry.MAX_DISPLACEMENT}) {
                float[] bounds=input.atHeight(cap);
                for(int t=0;t<2;t++)for(int a=0;a<=16;a++)for(int b=0;b<=16-a;b++) {
                    double u=a/16.0,v=b/16.0,w=1-u-v;
                    for(int axis=0;axis<3;axis++) {
                        double p=w*vertices[indices[t*3]*3+axis]+u*vertices[indices[t*3+1]*3+axis]+v*vertices[indices[t*3+2]*3+axis];
                        assertTrue(p>=bounds[t*6+axis]);
                        assertTrue(p+(axis==1?cap:0)<=bounds[t*6+3+axis]);
                    }
                }
                assertArrayEquals(original,packed.aabbs());
                bounds[0]=Float.NaN;
                assertFalse(Float.isNaN(input.atHeight(cap)[0]),"per-build arrays must not mutate retained input");
            }
        }
    }

    @Test void sidesAndBottomsRemainExactAndMalformedEnvelopesFailBeforeGpuWork() {
        var side=RtWaterGeometry.pack(new float[]{0,0,0,0,1,0,1,1,0,1,0,0},new int[]{0,1,2,0,2,3},0,2,null);
        var input=RtWaterGeometry.boundsInput(side);
        for(float cap:new float[]{0,.55f,2.2f})assertArrayEquals(side.aabbs(),input.atHeight(cap));
        var bottom=RtWaterGeometry.pack(new float[]{0,0,0,1,0,0,1,0,1,0,0,1},new int[]{0,1,2,0,2,3},0,2,null);
        assertEquals(0,bottom.topTriangles());
        for(float cap:new float[]{0,.55f,2.2f})assertArrayEquals(bottom.aabbs(),RtWaterGeometry.boundsInput(bottom).atHeight(cap));
        for(float invalid:new float[]{-1,2.3f,Float.NaN,Float.POSITIVE_INFINITY})
            assertThrows(IllegalArgumentException.class,()->input.atHeight(invalid));
    }
}
