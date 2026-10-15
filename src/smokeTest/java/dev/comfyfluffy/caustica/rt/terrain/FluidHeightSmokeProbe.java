package dev.comfyfluffy.caustica.rt.terrain;

import dev.comfyfluffy.caustica.compat.VoxyCompat;
/** Verify provider -> reflection snapshot -> production RT corner conversion without changing render counters. */
public final class FluidHeightSmokeProbe {
    private FluidHeightSmokeProbe() {}
    public static void verify(boolean requirePrecise) {
        for(var source:VoxyCompat.meshes()) {
            var mesh=source.textured();
            if(mesh==null || mesh.level()!=0 || source.originX()>64 || source.originX()+32<=64
                    || source.originY()>111 || source.originY()+32<=111
                    || source.originZ()>64 || source.originZ()+32<=64) continue;
            for(int i=0;i<mesh.quadCount();i++) {
                long raw=mesh.quad(i);
                if((raw&7)!=1 || source.originX()+((raw>>>21)&31)!=64
                        || source.originY()+((raw>>>16)&31)!=111 || source.originZ()+((raw>>>11)&31)!=64) continue;
                var model=mesh.model((int)(raw>>>26)&65535);
                if(!model.water()) continue;
                float[] vertices=new float[12],uv=new float[8];
                RtVoxyMesh.corners(raw,model.face(1),true,1,vertices,uv,mesh.fluidHeights(i));
                for(int vertex=0;vertex<4;vertex++) vertices[vertex*3+1]+=source.originY();
                float expected=111+8f/9f,error=0;
                for(int vertex=0;vertex<4;vertex++) error=Math.max(error,Math.abs(vertices[vertex*3+1]-expected));
                boolean precise=mesh.fluidHeights(i)!=0;
                System.out.println("GYSF_FLUID_PRECISION: precise="+precise+" expectedY="+expected
                        +" vertexY="+vertices[1]+" maxErrorBlocks="+error);
                if(requirePrecise && (!precise || error>1e-5f))
                    throw new IllegalStateException("Still water did not retain near-mesher height through the optional API");
                if(!precise && error<.01f) throw new IllegalStateException("Legacy control did not exercise height quantization");
                return;
            }
        }
        if(requirePrecise) throw new IllegalStateException("Level-zero fixture fluid quad was not available for the precision probe");
        System.out.println("GYSF_FLUID_PRECISION: no level-zero fixture sample");
    }
}
