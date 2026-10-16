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
        assertEquals(2,data.primitives()[3]);assertEquals(2,data.primitives()[23]);
        var expected=new RtWaterGeometry.SourceDomain(0,0,1,1,1.8f,2.2f,1.8f,1.9f);
        assertEquals(expected,RtWaterGeometry.sourceDomain(data.primitives(),0));
        assertEquals(expected,RtWaterGeometry.sourceDomain(data.primitives(),20));
    }

    @Test void nonplanarSourceDomainsAgreeAcrossBothDiagonalsAndCoincidentFineEdges() {
        for(int[] diagonal:new int[][]{mainDiagonal(),{0,1,3,1,2,3}})for(int scale:new int[]{1,2,4,8,16}) {
            float low=175+8f/9f,high=176.25f;
            var data=RtWaterGeometry.pack(new float[]{32,low,16,32,low,16+scale,32+scale,high+(high-low),16+scale,32+scale,high,16},diagonal,0,2,null);
            var a=RtWaterGeometry.sourceDomain(data.primitives(),0);
            assertEquals(a,RtWaterGeometry.sourceDomain(data.primitives(),20));
            for(int z=16;z<=16+scale;z++)assertEquals(175,a.layerAt(32,z),"shared low edge must keep the fine water layer");
            assertEquals(176,a.layerAt(32+scale,16));
        }
    }

    @Test void sourceLayerTiesSurviveAllMipQuantizationOffsetsAndSignedHeights() {
        for(int base=-64;base<=320;base+=16)for(int level=0;level<=4;level++) {
            int scale=1<<level;
            for(int offset=0;offset<=scale;offset++) {
                float decoded=Math.round((float)offset/scale*65535f)/65535f*scale+base;
                assertEquals(base+offset-1,RtWaterGeometry.sourceLayer(decoded),"full-cell mip tie "+decoded);
            }
        }
        for(float value:new float[]{-16.875f,-.125f,.125f,15.888889f})assertEquals((int)Math.floor(value),RtWaterGeometry.sourceLayer(value));
        assertEquals(0,RtWaterGeometry.sourceLayer(RtWaterGeometry.SOURCE_LAYER_SNAP*2));
    }

    @Test void varyingOwnerRequestsCoverAnIndependentNodeEnumerationAtSignedWorldAnchors() {
        for(int anchor:new int[]{-30000000,-1,0,30000000})for(int anchorY:new int[]{-128,0,128}) {
            float[] vertices={-15,-2.75f,-17,-15,-1.25f,15,17,1.25f,15,17,.25f,-17};
            var data=RtWaterGeometry.pack(vertices,mainDiagonal(),0,2,null);
            var expected=new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
            for(int x=-15;x<=18;x++)for(int z=-17;z<=16;z++) {
                double u=Math.clamp((x+15)/32.0,0,1),v=Math.clamp((z+17)/32.0,0,1);
                double y=(1-u)*((1-v)*-2.75+v*-1.25)+u*((1-v)*.25+v*1.25);
                int layer=(int)Math.ceil(y-RtWaterGeometry.SOURCE_LAYER_SNAP)-1;
                expected.add(dev.comfyfluffy.caustica.compat.VoxyWaterDomain.Tile.key(Math.floorDiv(anchor+x,16),anchorY+layer,Math.floorDiv(-anchor+z,16)));
            }
            long[] actual=RtWaterFieldImage.domainTiles(data,anchor,anchorY,-anchor);
            assertEquals(expected,new it.unimi.dsi.fastutil.longs.LongOpenHashSet(actual));
        }
    }

    @Test void sourceLayerToleranceMatchesItsShaderContract() throws Exception {
        String shader=Files.readString(Path.of("shaders/world/water_field.slang"));
        var value=Pattern.compile("WATER_SOURCE_LAYER_SNAP\\s*=\\s*([0-9.]+)").matcher(shader);
        assertTrue(value.find());assertEquals(RtWaterGeometry.SOURCE_LAYER_SNAP,Float.parseFloat(value.group(1)));
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
