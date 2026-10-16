package dev.comfyfluffy.caustica.rt.terrain;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

final class RtWaterGeometryTest {
    private static float[] square() { return new float[]{0,2,0, 0,2,1, 1,2,1, 1,2,0}; }
    private static int[] mainDiagonal() { return new int[]{0,1,2, 0,2,3}; }

    @Test void topPacketsKeepIndexedGeometryAndSharedEnvelope() {
        float[] weights={0,.25f,.5f,1};
        var data=RtWaterGeometry.pack(square(),mainDiagonal(),0,2,weights);
        assertEquals(2,data.topTriangles());assertEquals(2,data.triangleCount());
        assertEquals(208,data.bytes());
        assertArrayEquals(new float[]{0,2,0},java.util.Arrays.copyOfRange(data.primitives(),0,3));
        assertArrayEquals(new float[]{1,2,1},java.util.Arrays.copyOfRange(data.primitives(),8,11));
        for(int t=0;t<2;t++) {
            int start=t*RtWaterGeometry.PRIMITIVE_FLOATS;
            assertEquals(1,data.primitives()[start+3]);
            assertArrayEquals(weights,java.util.Arrays.copyOfRange(data.primitives(),start+16,start+20));
            assertTrue(data.aabbs()[t*6+1]<2);
            assertTrue(data.aabbs()[t*6+4]>2+RtWaterGeometry.MAX_DISPLACEMENT);
        }
        assertEquals(1,data.primitives()[7]);assertEquals(0,data.primitives()[27]);
    }

    @Test void bothQuadDiagonalsHaveDistinctTriangleDomains() {
        var data=RtWaterGeometry.pack(square(),new int[]{0,1,3, 1,2,3},0,2,null);
        assertEquals(2,data.topTriangles());
        assertEquals(2,data.primitives()[7]);assertEquals(3,data.primitives()[27]);
    }

    @Test void bottomAndSideFacesDoNotGrowIntoWaveSurfaces() {
        var bottom=RtWaterGeometry.pack(square(),new int[]{0,2,1, 0,3,2},0,2,null);
        var side=RtWaterGeometry.pack(new float[]{0,0,0, 0,1,0, 1,1,0, 1,0,0},mainDiagonal(),0,2,null);
        for(var data:new RtWaterGeometry.Packed[]{bottom,side}) {
            assertEquals(0,data.topTriangles());assertEquals(0,data.primitives()[3]);
            assertTrue(data.aabbs()[4]-data.aabbs()[1]<1.001);
        }
    }

    @Test void nonplanarQuadsAndPackedBucketOffsetsPreserveTheirVertices() {
        float[] vertices={0,112,0, 0,112.3f,1, 1,112.4f,1, 1,111.8f,0};
        var data=RtWaterGeometry.pack(vertices,new int[]{0,1,2,0,2,3,0,1,2,0,2,3},2,2,null);
        assertEquals(2,data.topTriangles());
        assertEquals(112.3f,data.primitives()[5]);assertEquals(111.8f,data.primitives()[29]);
        assertTrue(data.aabbs()[7]<111.8f);assertTrue(data.aabbs()[4]>112.4f+RtWaterGeometry.MAX_DISPLACEMENT);
    }

    @Test void paddingSurvivesFloatSpacingAtLargeLocalCoordinates() {
        float[] vertices=square();for(int i=0;i<vertices.length;i++)vertices[i]+=65536;
        var data=RtWaterGeometry.pack(vertices,mainDiagonal(),0,2,null);
        assertTrue(data.aabbs()[0]<65536);assertTrue(data.aabbs()[3]>65537);
        assertTrue(data.aabbs()[4]>65538+RtWaterGeometry.MAX_DISPLACEMENT);
    }

    @Test void sourceLayerIsSharedByBothHalvesAtFullCellAndNegativeHeights() {
        for(float y:new float[]{0,1,-1,4096,65536}) {
            float[] vertices=square();for(int i=1;i<vertices.length;i+=3)vertices[i]=y;
            var data=RtWaterGeometry.pack(vertices,mainDiagonal(),0,2,null);
            assertEquals(y-1,data.primitives()[11]);assertEquals(y-1,data.primitives()[31]);
        }
        float[] vertices={0,1.8f,0,0,2.2f,1,1,1.9f,1,1,1.8f,0};
        var data=RtWaterGeometry.pack(vertices,mainDiagonal(),0,2,null);
        assertEquals(2,data.primitives()[11]);assertEquals(2,data.primitives()[31]);
    }

    @Test void canonicalOwnerRequestsCrossTileEdgesWithSignedOrigins() {
        var data=RtWaterGeometry.pack(square(),mainDiagonal(),0,2,null);
        var keys=RtWaterFieldImage.domainTiles(data,-1,-16,15);
        assertEquals(4,keys.length);
        var owners=new it.unimi.dsi.fastutil.longs.LongOpenHashSet(keys);
        for(int x:new int[]{-1,0})for(int z:new int[]{0,1})
            assertTrue(owners.contains(dev.comfyfluffy.caustica.compat.VoxyWaterDomain.Tile.key(x,-15,z)));
        var bottom=RtWaterGeometry.pack(square(),new int[]{0,2,1,0,3,2},0,2,null);
        assertEquals(0,RtWaterFieldImage.domainTiles(bottom,0,0,0).length);
    }

    @Test void malformedInputFailsBeforeNativeAllocation() {
        assertThrows(IllegalArgumentException.class,()->RtWaterGeometry.pack(square(),mainDiagonal(),0,1,null));
        assertThrows(IllegalArgumentException.class,()->RtWaterGeometry.pack(square(),mainDiagonal(),1,2,null));
        assertThrows(IllegalArgumentException.class,()->RtWaterGeometry.pack(square(),new int[]{0,1,4,0,2,3},0,2,null));
        assertThrows(IllegalArgumentException.class,()->RtWaterGeometry.pack(square(),new int[]{0,0,0,0,2,3},0,2,null));
        assertThrows(IllegalArgumentException.class,()->RtWaterGeometry.pack(square(),mainDiagonal(),0,2,new float[3]));
        for(float invalid:new float[]{-1,2,Float.NaN,Float.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,()->RtWaterGeometry.pack(square(),mainDiagonal(),0,2,new float[]{0,1,0,invalid}));
        }
        float[] vertices=square();vertices[4]=Float.NaN;
        assertThrows(IllegalArgumentException.class,()->RtWaterGeometry.pack(vertices,mainDiagonal(),0,2,null));
        assertEquals(0,RtWaterGeometry.pack(new float[0],new int[0],0,0,null).bytes());
    }

    @Test void staticBoundsContainTheAuthoredSpectrumAtMaximumSlider() throws Exception {
        String shader=Files.readString(Path.of("shaders/world/water.slang"));
        var strength=Pattern.compile("WATER_WAVE_STRENGTH\\s*=\\s*([0-9.]+)").matcher(shader);
        var wavelength=Pattern.compile("WAVE_LAMBDA0\\s*=\\s*([0-9.]+)").matcher(shader);
        var ratio=Pattern.compile("WAVE_LAMBDA_RATIO\\s*=\\s*([0-9.]+)").matcher(shader);
        var amplitudes=Pattern.compile("WAVE_Q\\[WAVE_COUNT\\]\\s*=\\s*\\{([^}]+)").matcher(shader);
        var maximum=Pattern.compile("waterWaveStrengthScale\\(\\).*?clamp\\(worldPush.waterOpacity.y,\\s*0.0,\\s*([0-9.]+)").matcher(shader);
        assertTrue(strength.find()&&wavelength.find()&&ratio.find()&&amplitudes.find()&&maximum.find());
        double lambda=Double.parseDouble(wavelength.group(1)),sum=0;
        for(String q:amplitudes.group(1).split(",")) {
            double amplitude=Double.parseDouble(q.trim());assertTrue(amplitude>=0);
            sum+=amplitude*lambda/(2*Math.PI);lambda/=Double.parseDouble(ratio.group(1));
        }
        double envelope=sum*Double.parseDouble(strength.group(1))*Double.parseDouble(maximum.group(1));
        assertTrue(envelope<RtWaterGeometry.MAX_DISPLACEMENT,"water AABBs must enclose every authored wave phase");
    }
}
