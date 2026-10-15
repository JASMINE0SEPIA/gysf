package dev.comfyfluffy.caustica.rt.terrain;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.comfyfluffy.caustica.compat.DistantHorizonsCompat;
import dev.comfyfluffy.caustica.compat.VoxySceneData;
import dev.comfyfluffy.caustica.rt.material.RtMaterialRegistry;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Exercise the actual private capture/section packer, without adding a test API to production. */
class FluidCaptureNormalsTest {
    private static RtTerrainMesher.PackedSection capture(float[] xyz,boolean water) throws Exception {
        Class<?> sectionType=Class.forName(RtTerrainMesher.class.getName()+"$SectionMesh");
        Constructor<?> sectionCtor=sectionType.getDeclaredConstructor(); sectionCtor.setAccessible(true);
        Object section=sectionCtor.newInstance();
        Class<?> captureType=Class.forName(RtTerrainMesher.class.getName()+"$FluidCapture");
        Constructor<?> captureCtor=captureType.getDeclaredConstructor(); captureCtor.setAccessible(true);
        Object capture=captureCtor.newInstance();
        Constructor<?> snapshotCtor=RtMaterialRegistry.Snapshot.class.getDeclaredConstructor(long.class,Map.class,
                int[].class,int.class,int.class,List.class,List.class,List.class);
        snapshotCtor.setAccessible(true);
        Object snapshot=snapshotCtor.newInstance(1L,Map.of(),new int[32],42,43,List.of(),List.of(),List.of());
        set(capture,"cur",section); set(capture,"materials",snapshot); set(capture,"water",water);
        set(capture,"emission",water?0f:1f);
        for(int v=0;v<4;v++) ((VertexConsumer)capture).addVertex(xyz[v*3],xyz[v*3+1],xyz[v*3+2],
                0xff3f76e4,0,0,0,0,0,1,0);
        Method pack=RtTerrainMesher.class.getDeclaredMethod("packSection",sectionType,float[].class);
        pack.setAccessible(true);
        return (RtTerrainMesher.PackedSection)pack.invoke(null,section,new float[0]);
    }
    private static void set(Object target,String name,Object value) throws Exception {
        Field field=target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target,value);
    }
    private static void assertGeometricNormals(RtTerrainMesher.PackedSection mesh) {
        for(int tri=0;tri<mesh.indices().length/3;tri++) {
            int a=mesh.indices()[tri*3]*3,b=mesh.indices()[tri*3+1]*3,c=mesh.indices()[tri*3+2]*3;
            float[] p=mesh.positions(),m=mesh.material();
            float nx=m[tri*12],ny=m[tri*12+1],nz=m[tri*12+2];
            assertEquals(1,nx*nx+ny*ny+nz*nz,1e-6f);
            assertEquals(0,nx*(p[b]-p[a])+ny*(p[b+1]-p[a+1])+nz*(p[b+2]-p[a+2]),1e-6f,"triangle "+tri+" edge AB");
            assertEquals(0,nx*(p[c]-p[a])+ny*(p[c+1]-p[a+1])+nz*(p[c+2]-p[a+2]),1e-6f,"triangle "+tri+" edge AC");
        }
    }
    @Test void nonPlanarWaterAndLavaKeepEachTrianglesOwnGeometricNormal() throws Exception {
        float[] xyz={0,.2f,0, 0,.4f,1, 1,1,1, 1,.3f,0};
        for(boolean water:new boolean[]{true,false}) {
            var mesh=capture(xyz,water); assertGeometricNormals(mesh);
            assertNotEquals(mesh.material()[0],mesh.material()[12],.01f);
            assertEquals(water?42:43,Float.floatToRawIntBits(mesh.material()[8]));
            assertEquals(water?0:1,mesh.material()[3]);
        }
    }
    @Test void planarTopBottomAndSideNormalsAndOpticalTintRemainUnchanged() throws Exception {
        float[][] faces={
                {0,1,0, 0,1,1, 1,1,1, 1,1,0},
                {0,0,0, 1,0,0, 1,0,1, 0,0,1},
                {0,0,0, 0,1,0, 1,1,0, 1,0,0}};
        for(float[] xyz:faces) {
            var mesh=capture(xyz,true); assertGeometricNormals(mesh);
            assertArrayEquals(java.util.Arrays.copyOfRange(mesh.material(),0,3),java.util.Arrays.copyOfRange(mesh.material(),12,15));
            assertArrayEquals(new float[]{63/255f,118/255f,228/255f},java.util.Arrays.copyOfRange(mesh.material(),4,7),1e-6f);
        }
    }
    @Test void identicalPreciseFluidTrianglesProduceMatchingNearAndVoxyNormals() throws Exception {
        int[] heights={7282,21845,65535,19661}; long precise=0;
        for(int i=0;i<4;i++) precise|=(long)heights[i]<<(16*i);
        var model=new VoxySceneData.Model(1,0xf0f0,0xf0f0,0xf0f0,0xf0f0,0xf0f0,0xf0f0,0,-1,null,true,true,false,0);
        var models=new VoxySceneData.Model[2]; models[1]=model;
        var source=new DistantHorizonsCompat.LodMesh(1,1,0,0,0,32,1,new byte[0],new byte[0],
                new VoxySceneData.Mesh(new long[]{1L|(1L<<26)},new VoxySceneData.Palette(models,new int[0]),0,new long[]{precise}));
        var far=RtVoxyMesh.pack(List.of(new RtVoxyMesh.Slice(source,0,1)),0,0,m->42);
        var near=capture(far.positions(),true);
        assertGeometricNormals(near); assertGeometricNormals(far);
        for(int tri=0;tri<2;tri++) for(int axis=0;axis<3;axis++)
            assertEquals(far.material()[tri*12+axis],near.material()[tri*12+axis],1e-6f);
    }
}
