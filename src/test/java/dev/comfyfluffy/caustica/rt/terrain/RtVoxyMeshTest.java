package dev.comfyfluffy.caustica.rt.terrain;

import dev.comfyfluffy.caustica.compat.DistantHorizonsCompat;
import dev.comfyfluffy.caustica.compat.VoxySceneData;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class RtVoxyMeshTest {
    private static final int FULL=0xF0F0;
    private static long raw(int face,int model) { return face|((long)model<<26); }
    private static VoxySceneData.Model model(int id,int faceData,boolean fluid,boolean water) {
        return new VoxySceneData.Model(id,faceData,faceData,faceData,faceData,faceData,faceData,0,-1,null,fluid,water,false,0);
    }
    private static DistantHorizonsCompat.LodMesh source(long[] quads,VoxySceneData.Model... models) {
        VoxySceneData.Model[] entries=new VoxySceneData.Model[65536];
        for(var model:models) entries[model.id()]=model;
        return new DistantHorizonsCompat.LodMesh(1,1,1024,-64,-256,32,1,new byte[0],new byte[0],
                new VoxySceneData.Mesh(quads,new VoxySceneData.Palette(entries,new int[0]),0));
    }
    private static RtTerrainMesher.PackedSection pack(DistantHorizonsCompat.LodMesh mesh) {
        return RtVoxyMesh.pack(List.of(new RtVoxyMesh.Slice(mesh,0,mesh.textured().quadCount())),1024,-512,m -> 42);
    }

    @Test void allSixFacesWindOutwardAndKeepFractionalDepth() {
        for(int face=0;face<6;face++) {
            float[] p=new float[12],uv=new float[8];
            RtVoxyMesh.corners(raw(face,0),FULL|(32<<16),false,1,p,uv);
            int axis=(face>>1)==0?1:(face>>1)==1?2:0;
            for(int v=0;v<4;v++) assertEquals(.5f,p[v*3+axis]);
            float[] a={p[3]-p[0],p[4]-p[1],p[5]-p[2]}, b={p[6]-p[0],p[7]-p[1],p[8]-p[2]};
            float[] n={a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]};
            assertTrue(n[axis]*((face&1)==0?-1:1)>0,"face "+face);
        }
    }

    @Test void greedyGeometryRepeatsUvsAtVoxelScaleInsteadOfStretchingOneTexel() {
        float[] p=new float[12],uv=new float[8];
        RtVoxyMesh.corners(raw(1,0)|(3L<<3)|(2L<<21),FULL,false,4,p,uv);
        float maxX=Float.NEGATIVE_INFINITY,maxU=0;
        for(int i=0;i<4;i++) { maxX=Math.max(maxX,p[i*3]); maxU=Math.max(maxU,uv[i*2]); }
        assertEquals(24,maxX); assertEquals(4,maxU);
    }

    @Test void zeroDepthAndFullDepthRemainExactCellBoundaries() {
        float[] p=new float[12],uv=new float[8];
        RtVoxyMesh.corners(raw(1,0),FULL,false,1,p,uv); assertEquals(1,p[1]);
        RtVoxyMesh.corners(raw(1,0),FULL|(63<<16),false,1,p,uv); assertEquals(0,p[1]);
    }

    @Test void fluidCornerBitsAreHeightsRatherThanGreedyWidths() {
        int heights=0|(1<<3)|(4<<6)|(7<<9);
        long packed=raw(1,0)|((long)(heights&255)<<3)|((long)((heights>>>8)&15)<<42);
        float[] p=new float[12],uv=new float[8];
        RtVoxyMesh.corners(packed,FULL,true,2,p,uv);
        assertArrayEquals(new float[]{.25f,.5f,2,1.25f},new float[]{p[1],p[4],p[7],p[10]});
        for(int v=0;v<4;v++) { assertTrue(p[v*3]>=0 && p[v*3]<=2); assertTrue(p[v*3+2]>=0 && p[v*3+2]<=2); }
    }

    @Test void sideFluidFacesKeepBottomFixedAndAdjustOnlyTheirTopCorners() {
        int heights=0|(1<<3)|(4<<6)|(7<<9);
        float[] p=new float[12],uv=new float[8];
        long packed=raw(2,0)|((long)(heights&255)<<3)|((long)((heights>>>8)&15)<<42);
        RtVoxyMesh.corners(packed,FULL,true,1,p,uv);
        assertArrayEquals(new float[]{0,.125f,.625f,0},new float[]{p[1],p[4],p[7],p[10]});
    }

    @Test void packedSlabRetainsFractionalPositionsAndAtlasMaterialIdentity() {
        var packed=pack(source(new long[]{raw(1,7)},model(7,FULL|(32<<16)|(1<<22),false,false)));
        assertEquals(-63.5f,packed.positions()[1]); assertEquals(256,packed.positions()[2]);
        assertEquals(42,Float.floatToRawIntBits(packed.material()[8]));
        assertEquals(RtVoxyMesh.PRIM_VOXY|1|32,Float.floatToRawIntBits(packed.material()[9]));
        assertEquals(7,Float.floatToRawIntBits(packed.material()[10]));
        assertEquals(0,Float.floatToRawIntBits(packed.material()[11]));
        assertEquals(1,packed.material()[1]);
        assertArrayEquals(new int[]{0,2,0,0},packed.bucketTris());
    }

    @Test void waterIsLastAndEveryLodTriangleRemainsInNonOpaqueBuckets() {
        var packed=pack(source(new long[]{raw(1,3),raw(1,2),raw(1,1)},
                model(1,FULL,false,false),model(2,FULL,false,false),model(3,FULL,true,true)));
        assertArrayEquals(new int[]{0,4,0,2},packed.bucketTris());
        assertArrayEquals(new int[]{0,0,4,4},packed.triBase());
        assertEquals(1,packed.material()[4*12+7]);
    }

    @Test void snapshotsRetainTheirOwnGeometryAndPalette() {
        var models=new VoxySceneData.Model[2]; models[1]=model(1,FULL,false,false);
        int[] colours={0x123456}; long[] quads={raw(1,1)};
        var palette=new VoxySceneData.Palette(models,colours);
        var mesh=new VoxySceneData.Mesh(quads,palette,0);
        models[1]=null; colours[0]=0; quads[0]=0;
        assertEquals(1,mesh.model(1).id()); assertEquals(0x123456,mesh.colour(0));
        assertEquals(raw(1,1),mesh.quad(0)); assertEquals(-1,mesh.colour(-1));
        assertThrows(IllegalStateException.class,()->mesh.model(0));
    }

    @Test void invalidFaceIsRejectedBeforeArrayWrites() {
        assertThrows(IllegalArgumentException.class,()->RtVoxyMesh.corners(raw(6,0),FULL,false,1,new float[12],new float[8]));
    }
}
