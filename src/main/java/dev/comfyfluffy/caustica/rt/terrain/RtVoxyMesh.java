package dev.comfyfluffy.caustica.rt.terrain;

import dev.comfyfluffy.caustica.compat.DistantHorizonsCompat;
import dev.comfyfluffy.caustica.compat.VoxySceneData;
import dev.comfyfluffy.caustica.rt.material.RtMaterialRegistry;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToIntFunction;

/** API 5 packed quads become float RT triangles without colour or whole-block geometry quantization. */
final class RtVoxyMesh {
    static final int PRIM_VOXY=0x80000000;
    static final AtomicLong PACKED_BATCHES=new AtomicLong();
    static final AtomicLong LEAF_CUTOUT_QUADS=new AtomicLong();
    static final AtomicLong WATER_QUADS=new AtomicLong();
    static final AtomicLong PRECISE_FLUID_QUADS=new AtomicLong();
    record Slice(DistantHorizonsCompat.LodMesh mesh,int start,int end) {}
    private RtVoxyMesh() {}

    static RtTerrainMesher.PackedSection pack(List<Slice> slices,int originX,int originZ,
                                               RtMaterialRegistry.Snapshot materials) {
        return pack(slices,originX,originZ,model -> model.water() ? materials.waterId() :
                model.fluid() && model.emission()>0 ? materials.lavaId() :
                materials.resolve(null,model.state(),model.glass()));
    }

    static RtTerrainMesher.PackedSection pack(List<Slice> slices,int originX,int originZ,
                                               ToIntFunction<VoxySceneData.Model> resolve) {
        int count=0,waterCount=0;
        for(var slice:slices) {
            count=Math.addExact(count,slice.end-slice.start);
            for(int i=slice.start;i<slice.end;i++) {
                var mesh=slice.mesh.textured();
                if(mesh.model((int)(mesh.quad(i)>>>26)&65535).water()) waterCount++;
            }
        }
        int ordinary=count-waterCount;
        float[] positions=new float[Math.multiplyExact(count,12)];
        int[] indices=new int[Math.multiplyExact(count,6)];
        float[] uvs=new float[Math.multiplyExact(count,12)], prims=new float[Math.multiplyExact(count,24)];
        int nextOrdinary=0,nextWater=ordinary,leafCutouts=0,preciseFluids=0;
        float[] p=new float[12],uv=new float[8];
        for(var slice:slices) {
            var source=slice.mesh;
            var mesh=source.textured();
            for(int i=slice.start;i<slice.end;i++) {
                long raw=mesh.quad(i);
                int face=(int)raw&7,modelId=(int)(raw>>>26)&65535;
                var model=mesh.model(modelId);
                int data=model.face(face),scale=1<<mesh.level();
                long precise=mesh.fluidHeights(i);
                corners(raw,data,model.fluid(),scale,p,uv,precise);
                if(model.fluid() && precise!=0) preciseFluids++;
                int q=model.water()?nextWater++:nextOrdinary++;
                int vertex=q*4;
                for(int v=0;v<4;v++) {
                    positions[q*12+v*3]=p[v*3]+source.originX()-originX;
                    positions[q*12+v*3+1]=p[v*3+1]+source.originY();
                    positions[q*12+v*3+2]=p[v*3+2]+source.originZ()-originZ;
                }
                int[] order={0,1,2,0,2,3};
                for(int v=0;v<6;v++) {
                    indices[q*6+v]=vertex+order[v];
                    uvs[q*12+v*2]=uv[order[v]*2]; uvs[q*12+v*2+1]=uv[order[v]*2+1];
                }
                int tint=(model.flags()&2)!=0 && model.tint()>=0
                        ? mesh.colour(model.tint()+((int)(raw>>>46)&511)) : model.tint();
                boolean cutout=(data&(1<<22))!=0 || (!model.fluid() && (data&(1<<23))!=0 && ((raw>>>3)&255)!=0);
                int flags=PRIM_VOXY|face|(((data>>>24)&3)<<3)|(cutout?32:0);
                if(model.state()!=null && model.state().is(net.minecraft.tags.BlockTags.LEAVES)) flags|=64;
                if((flags&96)==96) leafCutouts++;
                int kind=model.water()?1:model.glass()?(model.emission()>0?4:2):model.emission()>0?3:0;
                int material=resolve.applyAsInt(model);
                for(int tri=0;tri<2;tri++) {
                    int offset=q*24+tri*12;
                    int a=order[tri*3]*3,b=order[tri*3+1]*3,c=order[tri*3+2]*3;
                    float ax=p[b]-p[a],ay=p[b+1]-p[a+1],az=p[b+2]-p[a+2];
                    float bx=p[c]-p[a],by=p[c+1]-p[a+1],bz=p[c+2]-p[a+2];
                    float nx=ay*bz-az*by,ny=az*bx-ax*bz,nz=ax*by-ay*bx;
                    float length=(float)Math.sqrt(nx*nx+ny*ny+nz*nz);
                    if(length<=0) throw new IllegalStateException("Voxy published a degenerate quad");
                    prims[offset]=nx/length; prims[offset+1]=ny/length; prims[offset+2]=nz/length;
                    prims[offset+3]=model.emission()/15f;
                    // Water tint is an optical coefficient, matching FluidCapture's normalized biome
                    // colour. Ordinary albedo tint uses the atlas shader's linear colour convention.
                    prims[offset+4]=tintChannel(tint==-1?255:(tint>>>16)&255,model.water());
                    prims[offset+5]=tintChannel(tint==-1?255:(tint>>>8)&255,model.water());
                    prims[offset+6]=tintChannel(tint==-1?255:tint&255,model.water());
                    prims[offset+7]=kind;
                    prims[offset+8]=Float.intBitsToFloat(material);
                    prims[offset+9]=Float.intBitsToFloat(flags);
                    prims[offset+10]=Float.intBitsToFloat(modelId);
                    prims[offset+11]=Float.intBitsToFloat(mesh.level());
                }
            }
        }
        PACKED_BATCHES.incrementAndGet();
        LEAF_CUTOUT_QUADS.addAndGet(leafCutouts);
        WATER_QUADS.addAndGet(waterCount);
        PRECISE_FLUID_QUADS.addAndGet(preciseFluids);
        // Every LOD face traverses any-hit, including solid faces, for the vanilla-ready ownership mask.
        return new RtTerrainMesher.PackedSection(positions,indices,uvs,prims,
                new int[]{0,ordinary*2,0,waterCount*2},new int[]{0,0,ordinary*2,ordinary*2},new float[0]);
    }

    static void corners(long raw,int data,boolean fluid,int scale,float[] p,float[] uv) {
        corners(raw,data,fluid,scale,p,uv,0);
    }

    static void corners(long raw,int data,boolean fluid,int scale,float[] p,float[] uv,long precise) {
        int face=(int)raw&7,axis=face>>1;
        if(face>5) throw new IllegalArgumentException("Voxy face outside 0..5");
        int au=axis==2?1:0,av=axis==0?2:axis==1?1:2,an=axis==0?1:axis==1?2:0;
        float u=(data&15)/16f,v=((data>>>8)&15)/16f;
        float width=(((data>>>4)&15)+1)/16f-u+(fluid?0:(raw>>>3)&15);
        float height=(((data>>>12)&15)+1)/16f-v+(fluid?0:(raw>>>7)&15);
        int depth=(data>>>16)&63;
        float d=fluid?0:(depth==63?1:depth/64f);
        if((face&1)!=0) d=1-d;
        float[] base={(raw>>>21)&31,(raw>>>16)&31,(raw>>>11)&31};
        base[an]+=d; base[au]+=fluid?0:u; base[av]+=fluid?0:v;
        int heights=(int)((raw>>>3)&255)|((int)((raw>>>42)&15)<<8);
        boolean reverse=((face&1)==0?-1:1)!=(an==1?-1:1);
        int[] order=reverse?new int[]{0,3,2,1}:new int[]{0,1,2,3};
        int[] ub={0,1,1,0},vb={0,0,1,1};
        for(int j=0;j<4;j++) {
            int i=order[j],a=ub[i],b=vb[i];
            for(int k=0;k<3;k++) p[j*3+k]=(base[k]+(k==au?a*(fluid?1:width):0)+(k==av?b*(fluid?1:height):0))*scale;
            uv[j*2]=u+a*width; uv[j*2+1]=v+b*height;
            if(fluid) {
                int corner=-1;
                if(face==1) corner=(a<<1)|b;
                else if(axis==1 && b==1) corner=(a<<1)|(face&1);
                else if(axis==2 && a==1) corner=((face&1)<<1)|b;
                if(corner>=0) {
                    float heightValue=precise!=0?((precise>>>(corner*16))&65535L)/65535f:
                            (((heights>>>(corner*3))&7)+1)/8f;
                    p[j*3+1]+=(heightValue-1)*scale;
                }
            }
        }
    }

    private static float tintChannel(int value,boolean water) {
        float f=value/255f;
        return water?f:f<=0.04045f?f/12.92f:(float)Math.pow((f+0.055f)/1.055f,2.4);
    }
}
