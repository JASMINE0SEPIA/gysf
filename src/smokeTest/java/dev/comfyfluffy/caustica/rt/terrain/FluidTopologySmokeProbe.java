package dev.comfyfluffy.caustica.rt.terrain;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.comfyfluffy.caustica.compat.VoxyCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import java.util.*;

/** Real near mesher versus exported level-zero RT geometry, in an isolated frozen fixture. */
public final class FluidTopologySmokeProbe {
    private record Sample(String name, BlockPos pos, int variant) { }
    private static final List<Sample> SAMPLES=List.of(
            sample("still",56,32,0), sample("flow",64,32,1),
            sample("open-shore",72,32,2), sample("solid-shore",80,32,3),
            sample("stone-ceiling",56,40,4), sample("glass-ceiling",64,40,5),
            sample("glass-east",72,40,6), sample("glass-south",80,40,7),
            sample("bottom-slab-ceiling",56,48,8), sample("top-slab-ceiling",64,48,9),
            sample("fluid-above",72,48,10), sample("waterlogged-slab",80,48,11),
            sample("section-edge-flow",63,63,1), sample("section-edge-glass-ceiling",63,56,5));
    private static Sample sample(String name,int x,int z,int variant) {
        return new Sample(name,new BlockPos(x,127,z),variant);
    }
    private FluidTopologySmokeProbe() { }
    private static Map<BlockPos,BlockState> expected=Map.of();
    private static boolean edited;
    private static String phase="static";

    private static void set(ServerLevel level,Map<BlockPos,BlockState> states,BlockPos pos,BlockState state) {
        level.setBlock(pos,state,2); states.put(pos.immutable(),state);
    }

    public static void place(MinecraftServer server) {
        edited=false;
        phase="static";
        server.tickRateManager().setFrozen(true);
        var level=server.overworld();
        var states=new HashMap<BlockPos,BlockState>();
        for(var sample:SAMPLES) {
            BlockPos p=sample.pos;
            for(int x=-2;x<=2;x++) for(int y=-1;y<=2;y++) for(int z=-2;z<=2;z++)
                set(level,states,p.offset(x,y,z),y==-1?Blocks.QUARTZ_BLOCK.defaultBlockState():Blocks.AIR.defaultBlockState());
            for(int x=-1;x<=1;x++) for(int z=-1;z<=1;z++) {
                BlockState state=Blocks.WATER.defaultBlockState();
                if(sample.variant==1) state=state.setValue(BlockStateProperties.LEVEL,(x+1)*2+(z+1));
                if(sample.variant==2 && x==1) state=Blocks.AIR.defaultBlockState();
                if(sample.variant==3 && x==1) state=Blocks.STONE.defaultBlockState();
                set(level,states,p.offset(x,0,z),state);
            }
            BlockState ceiling=switch(sample.variant) {
                case 4 -> Blocks.STONE.defaultBlockState();
                case 5 -> Blocks.GLASS.defaultBlockState();
                case 8 -> Blocks.STONE_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE,SlabType.BOTTOM);
                case 9 -> Blocks.STONE_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE,SlabType.TOP);
                case 10 -> Blocks.WATER.defaultBlockState();
                default -> Blocks.AIR.defaultBlockState();
            };
            if(!ceiling.isAir()) set(level,states,p.above(),ceiling);
            if(sample.variant==6) set(level,states,p.east(),Blocks.GLASS.defaultBlockState());
            if(sample.variant==7) set(level,states,p.south(),Blocks.GLASS.defaultBlockState());
            if(sample.variant==11) set(level,states,p,Blocks.STONE_SLAB.defaultBlockState()
                    .setValue(BlockStateProperties.WATERLOGGED,true));
        }
        expected=Map.copyOf(states);
        System.out.println("GYSF_FLUID_TOPOLOGY: placed "+SAMPLES.size()+" frozen samples at y=127");
    }

    public static void editDiagonalCeiling(MinecraftServer server) {
        diagonalCeiling(server,true);
    }
    public static void restoreDiagonalCeiling(MinecraftServer server) {
        diagonalCeiling(server,false);
    }
    private static void diagonalCeiling(MinecraftServer server,boolean present) {
        var states=new HashMap<>(expected);
        set(server.overworld(),states,new BlockPos(63,128,63),(present?Blocks.GLASS:Blocks.AIR).defaultBlockState());
        expected=Map.copyOf(states); edited=true; phase=present?"added":"restored";
        System.out.println("GYSF_FLUID_TOPOLOGY: "+phase+" diagonal ceiling at 63,128,63");
    }

    public static void verify(Minecraft client,boolean allowMismatch) {
        int mismatches=0,faces=0;
        float worst=0;
        if(expected.isEmpty()) throw new IllegalStateException("Topology placement was not published");
        for(var entry:expected.entrySet()) if(!client.level.getBlockState(entry.getKey()).equals(entry.getValue()))
            throw new IllegalStateException("Topology state not received at "+entry.getKey()+": expected "+entry.getValue());
        var snapshots=new RtSectionSnapshots();
        var checks=new ArrayList<>(SAMPLES);
        checks.add(sample("stone-rim",57,40,0)); checks.add(sample("glass-rim",65,40,0));
        checks.add(sample("bottom-slab-rim",57,48,0)); checks.add(sample("section-edge-glass-rim",64,56,0));
        if(edited) {
            checks.add(sample("edited-east",64,63,0)); checks.add(sample("edited-south",63,64,0));
            checks.add(sample("edited-diagonal",64,64,0));
        }
        for(var sample:checks) {
            BlockPos p=sample.pos;
            var region=snapshots.createRegion(client.level,p.getX()>>4,p.getY()>>4,p.getZ()>>4);
            var state=region.getBlockState(p);
            if(state.getFluidState().isEmpty()) throw new IllegalStateException("Fixture not received: "+sample.name);
            var capture=new Capture(p);
            RtFluidMesher.tesselate(region,p,capture,client.getModelManager().getFluidStateModelSet(),state,state.getFluidState());
            Map<Integer,float[]> far=new HashMap<>();
            boolean foundSection=false;
            for(var source:VoxyCompat.meshes()) {
                var mesh=source.textured();
                if(mesh==null || mesh.level()!=0 || p.getX()<source.originX() || p.getX()>=source.originX()+32
                        || p.getY()<source.originY() || p.getY()>=source.originY()+32
                        || p.getZ()<source.originZ() || p.getZ()>=source.originZ()+32) continue;
                foundSection=true;
                for(int i=0;i<mesh.quadCount();i++) {
                    long raw=mesh.quad(i);
                    if(p.getX()!=source.originX()+((raw>>>21)&31) || p.getY()!=source.originY()+((raw>>>16)&31)
                            || p.getZ()!=source.originZ()+((raw>>>11)&31)) continue;
                    var model=mesh.model((int)(raw>>>26)&65535);
                    if(!model.water()) continue;
                    int face=(int)raw&7;
                    float[] xyz=new float[12];
                    RtVoxyMesh.corners(raw,model.face(face),true,1,xyz,new float[8],mesh.fluidHeights(i));
                    for(int v=0;v<4;v++) { xyz[v*3]+=source.originX(); xyz[v*3+1]+=source.originY(); xyz[v*3+2]+=source.originZ(); }
                    if(far.put(face,xyz)!=null) throw new IllegalStateException("Duplicate provider water face: "+sample.name+"/"+face);
                }
            }
            if(!foundSection) throw new IllegalStateException("Level-zero fixture section missing: "+sample.name);
            Set<Integer> union=new TreeSet<>(capture.quads.keySet()); union.addAll(far.keySet());
            for(int face:union) {
                float[] a=capture.quads.get(face),b=far.get(face);
                float error=a==null || b==null ? Float.POSITIVE_INFINITY : vertexError(a,b);
                faces++;
                if(error>2e-5f) {
                    mismatches++;
                    System.out.println("GYSF_FLUID_TOPOLOGY: mismatch sample="+sample.name+" face="+face
                            +" near="+Arrays.toString(a)+" far="+Arrays.toString(b)+" maxError="+error);
                }
                if(Float.isFinite(error)) worst=Math.max(worst,error);
            }
            System.out.println("GYSF_FLUID_TOPOLOGY: sample="+sample.name+" nearFaces="+capture.quads.keySet()+" farFaces="+new TreeSet<>(far.keySet()));
        }
        System.out.println("GYSF_FLUID_TOPOLOGY: phase="+phase+" edited="+edited+" samples="+checks.size()+" faces="+faces+" mismatches="+mismatches+" maxMatchedVertexError="+worst);
        if(!allowMismatch && mismatches!=0) throw new IllegalStateException("Near/LOD fluid topology mismatch: "+mismatches);
    }

    private static float vertexError(float[] a,float[] b) {
        float worst=0;
        for(int v=0;v<4;v++) {
            float best=Float.POSITIVE_INFINITY;
            for(int w=0;w<4;w++) {
                float error=0;
                for(int axis=0;axis<3;axis++) error=Math.max(error,Math.abs(a[v*3+axis]-b[w*3+axis]));
                best=Math.min(best,error);
            }
            worst=Math.max(worst,best);
        }
        return worst;
    }

    private static final class Capture implements VertexConsumer,FluidRenderer.Output {
        private final BlockPos pos;
        private final Map<Integer,float[]> quads=new HashMap<>();
        private float[] xyz=new float[12];
        private int n;
        Capture(BlockPos pos) { this.pos=pos; }
        @Override public VertexConsumer getBuilder(ChunkSectionLayer layer) { return this; }
        @Override public void addVertex(float x,float y,float z,int color,float u,float v,int overlay,int light,float nx,float ny,float nz) {
            xyz[n*3]=x+(pos.getX()&~15); xyz[n*3+1]=y+(pos.getY()&~15); xyz[n*3+2]=z+(pos.getZ()&~15);
            if(++n==4) {
                int face=plane(0,pos.getX())?4:plane(0,pos.getX()+1)?5:plane(2,pos.getZ())?2:plane(2,pos.getZ()+1)?3:plane(1,pos.getY())?0:1;
                if(quads.put(face,xyz)!=null) throw new IllegalStateException("Duplicate near water face");
                n=0; xyz=new float[12];
            }
        }
        private boolean plane(int axis,int value) { for(int v=0;v<4;v++) if(xyz[v*3+axis]!=value) return false; return true; }
        @Override public VertexConsumer addVertex(float x,float y,float z) { return this; }
        @Override public VertexConsumer setColor(int r,int g,int b,int a) { return this; }
        @Override public VertexConsumer setColor(int color) { return this; }
        @Override public VertexConsumer setUv(float u,float v) { return this; }
        @Override public VertexConsumer setUv1(int u,int v) { return this; }
        @Override public VertexConsumer setUv2(int u,int v) { return this; }
        @Override public VertexConsumer setUv3(float u,float v) { return this; }
        @Override public VertexConsumer setNormal(float x,float y,float z) { return this; }
        @Override public VertexConsumer setLineWidth(float width) { return this; }
    }
}
