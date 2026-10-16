package dev.comfyfluffy.caustica.rt.terrain;

import com.google.gson.GsonBuilder;
import dev.comfyfluffy.caustica.compat.VoxyWaterDomain;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.lwjgl.system.MemoryUtil;
import java.nio.*;
import java.nio.file.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Actual provider mip geometry and canonical source data; no synthetic replacement vertices. */
public final class WaterSourceSmokeProbe {
    public static final boolean ENABLED=Boolean.getBoolean("gysf.smoke.waterSourceCapture");
    private static final int ANCHOR_Y=128,CASES=4096;
    private record Quad(int level,float[] vertices,RtWaterGeometry.Packed packed) {}
    private static final List<Quad> quads=new ArrayList<>();
    private static CompletableFuture<?> placement;
    private static int[] requests;
    private static VoxyWaterDomain.Snapshot snapshot;
    private static boolean written;
    private WaterSourceSmokeProbe() {}

    private static int top(int x) {return 174+Math.floorDiv(x-32,16);}
    private static void place(MinecraftServer server) {
        server.tickRateManager().setFrozen(true);
        var level=server.overworld();var pos=new BlockPos.MutableBlockPos();
        for(int x=32;x<=127;x++)for(int z=0;z<=95;z++)for(int y=159;y<=207;y++) {
            int height=top(x);
            var state=y==159||(y<=height&&(x==32||x==127||z==0||z==95))?Blocks.QUARTZ_BLOCK.defaultBlockState()
                    :y<height?Blocks.WATER.defaultBlockState():y==height
                    ?Blocks.WATER.defaultBlockState().setValue(BlockStateProperties.LEVEL,Math.floorDiv(z,4)&7)
                    :Blocks.AIR.defaultBlockState();
            if(z>=40&&z<=43&&y==182)state=Blocks.GLASS.defaultBlockState();
            if(x>=62&&x<=65&&z>=56&&z<=59&&y==height+1)state=Blocks.STONE.defaultBlockState();
            level.setBlock(pos.set(x,y,z),state,2);
        }
        System.out.println("GYSF_WATER_SOURCE: placed frozen stepped/flowing/covered source pool");
    }

    public static void tick(Minecraft client,int tick) {
        if(!ENABLED)return;
        if(tick==350)placement=client.getSingleplayerServer().submit(()->place(client.getSingleplayerServer()));
        if(tick==1000) {
            if(placement==null||!placement.isDone())throw new IllegalStateException("Source fixture not placed");
            placement.join();
            for(int x:new int[]{48,64,80,96,112})for(int z:new int[]{24,48,72}) {
                var state=client.level.getBlockState(new BlockPos(x,top(x),z));
                if(!state.is(Blocks.WATER)||state.getValue(BlockStateProperties.LEVEL)!=(Math.floorDiv(z,4)&7))
                    throw new IllegalStateException("Source fixture not received at "+x+","+z);
            }
            collect(client);
        }
        if(requests!=null&&!written&&tick<=1250) {
            snapshot=VoxyWaterDomain.poll(requests);
            if(snapshot!=null&&snapshot.pending()==0&&snapshot.tiles().size()==requests.length/3)write();
        }
        if(tick==1250&&!written)throw new IllegalStateException("Captured source field did not settle");
    }

    private static Object field(Object object,String name)throws ReflectiveOperationException {
        Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);
    }
    private static void collect(Minecraft client) {
        try {
            Object renderer=Class.forName("me.cortex.voxy.client.core.IVoxyRenderSystemHolder").getMethod("getNullable").invoke(null);
            Object core=renderer.getClass().getField("vkCore").get(renderer);
            Object world=core.getClass().getMethod("getEngine").invoke(core);
            Object models=field(core,"modelService").getClass().getField("factory").get(field(core,"modelService"));
            Class<?> sectionType=Class.forName("me.cortex.voxy.common.world.WorldSection");
            Class<?> factoryType=Class.forName("me.cortex.voxy.client.core.rendering.building.RenderDataFactory");
            var constructor=factoryType.getConstructor(world.getClass(),models.getClass(),boolean.class,boolean.class,boolean.class,int.class,int.class);
            var owners=new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
            for(int level=0;level<=4;level++) {
                int scale=1<<level,width=32*scale,sx=Math.floorDiv(64,width),sy=Math.floorDiv(176,width),sz=Math.floorDiv(48,width);
                Object section=world.getClass().getMethod("acquire",int.class,int.class,int.class,int.class).invoke(world,level,sx,sy,sz);
                Object factory=constructor.newInstance(world,models,false,false,true,client.level.getMinY(),client.level.getHeight());
                Object built=null;
                try {
                    built=factoryType.getMethod("generateMesh",sectionType).invoke(factory,section);
                    Object buffer=built.getClass().getField("geometryBuffer").get(built);
                    if(buffer==null)throw new IllegalStateException("No source mip geometry: "+level);
                    int count=Math.toIntExact(buffer.getClass().getField("size").getLong(buffer)/8);
                    var rawQuads=MemoryUtil.memLongBuffer(buffer.getClass().getField("address").getLong(buffer),count);
                    long[] heights=(long[])built.getClass().getField("fluidHeights").get(built);
                    var varying=new ArrayList<Quad>();var constant=new ArrayList<Quad>();
                    for(int i=0;i<count;i++) {
                        long raw=rawQuads.get(i);
                        if((raw&7)!=1||heights.length!=count||heights[i]==0)continue;
                        float[] p=new float[12];RtVoxyMesh.corners(raw,0,true,scale,p,new float[8],heights[i]);
                        boolean inside=true;
                        for(int v=0;v<4;v++) {
                            p[v*3]+=sx*width;p[v*3+1]+=sy*width-ANCHOR_Y;p[v*3+2]+=sz*width;
                            inside&=p[v*3]>=32&&p[v*3]<=128&&p[v*3+2]>=0&&p[v*3+2]<=96&&p[v*3+1]>=42&&p[v*3+1]<=57;
                        }
                        if(!inside)continue;
                        var packed=RtWaterGeometry.pack(p,new int[]{0,1,2,0,2,3},0,2,null);
                        if(packed.topTriangles()!=2)continue;
                        (packed.primitives()[3]==2?varying:constant).add(new Quad(level,p,packed));
                    }
                    if(varying.isEmpty()&&constant.isEmpty())throw new IllegalStateException("No actual water tops at mip "+level);
                    // Bounded, distributed samples retain varying domains first, then flat controls.
                    for(var list:List.of(varying,constant)) {
                        int take=Math.min(64,list.size());
                        for(int n=0;n<take;n++) {
                            Quad q=list.get(n*list.size()/take);quads.add(q);
                            for(long key:RtWaterFieldImage.domainTiles(q.packed,0,ANCHOR_Y,0))owners.add(key);
                        }
                    }
                    System.out.println("GYSF_WATER_SOURCE: mip="+level+" varying="+varying.size()+" constant="+constant.size());
                } finally {
                    if(built!=null)built.getClass().getMethod("free").invoke(built);
                    factoryType.getMethod("free").invoke(factory);sectionType.getMethod("release").invoke(section);
                }
            }
            if(quads.stream().noneMatch(q->q.packed.primitives()[3]==2))throw new IllegalStateException("No varying source domain captured");
            if(owners.size()>RtWaterFieldImage.MAX_TILES)throw new IllegalStateException("Captured owner budget exceeded");
            // Actual provider responses for unvisited space, independent of known dry/covered nodes.
            for(long key:owners.toLongArray())owners.add(VoxyWaterDomain.Tile.key(RtWaterFieldImage.x(key)+4096,RtWaterFieldImage.y(key),RtWaterFieldImage.z(key)+4096));
            long[] keys=owners.toLongArray();Arrays.sort(keys);requests=new int[keys.length*3];
            for(int i=0;i<keys.length;i++) {requests[i*3]=RtWaterFieldImage.x(keys[i]);requests[i*3+1]=RtWaterFieldImage.y(keys[i]);requests[i*3+2]=RtWaterFieldImage.z(keys[i]);}
        } catch(ReflectiveOperationException e){throw new IllegalStateException("Actual provider source capture failed",e);}
    }

    /** Independent double-precision domain oracle reads source tiles, never the GPU image/helpers. */
    private static float weight(Quad q,float x,float z,float strength) {
        float[] p=q.vertices;
        double minX=Double.POSITIVE_INFINITY,minZ=minX,maxX=Double.NEGATIVE_INFINITY,maxZ=maxX;
        for(int v=0;v<4;v++){minX=Math.min(minX,p[v*3]);maxX=Math.max(maxX,p[v*3]);minZ=Math.min(minZ,p[v*3+2]);maxZ=Math.max(maxZ,p[v*3+2]);}
        double[] heights=new double[4];
        for(int v=0;v<4;v++)heights[(p[v*3]>minX?2:0)|(p[v*3+2]>minZ?1:0)]=p[v*3+1];
        int cellX=(int)Math.floor(x),cellZ=(int)Math.floor(z);double[] w=new double[4];
        for(int i=0;i<4;i++) {
            int nx=cellX+(i>>1),nz=cellZ+(i&1);
            double u=Math.clamp((nx-minX)/(maxX-minX),0,1),v=Math.clamp((nz-minZ)/(maxZ-minZ),0,1);
            double height=(1-u)*((1-v)*heights[0]+v*heights[1])+u*((1-v)*heights[2]+v*heights[3]);
            int layer=(int)Math.ceil(height-.001)-1+ANCHOR_Y;
            var tile=snapshot.tile(Math.floorDiv(nx,16),layer,Math.floorDiv(nz,16));
            if(tile==null)throw new IllegalStateException("Missing independent source node owner");
            w[i]=Math.min(tile.clearance(Math.floorMod(nx,16),Math.floorMod(nz,16))/(.55*strength),1);
        }
        double u=x-cellX,v=z-cellZ;u=u*u*(3-2*u);v=v*v*(3-2*v);
        return (float)((1-u)*((1-v)*w[0]+v*w[1])+u*((1-v)*w[2]+v*w[3]));
    }
    private static void write() {
        try {
            Path root=Path.of(Objects.requireNonNull(System.getProperty("gysf.smoke.sourceDir")));
            if(Files.exists(root))throw new IllegalStateException("Source output must be fresh");Files.createDirectories(root);
            ByteBuffer packets=ByteBuffer.allocate(CASES*3*RtWaterGeometry.PRIMITIVE_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            int zero=0,wet=0,partial=0,varying=0;int[] levels=new int[5];
            for(int c=0;c<CASES;c++) {
                Quad q=quads.get(c%quads.size());float t=(1+(c/quads.size())%7)/8f;
                float x=q.vertices[0]+(q.vertices[6]-q.vertices[0])*t,z=q.vertices[2]+(q.vertices[8]-q.vertices[2])*t;
                float y=q.vertices[1]+(q.vertices[7]-q.vertices[1])*t;
                for(float v:q.packed.primitives())packets.putFloat(v);
                float w1=weight(q,x,z,1),w4=weight(q,x,z,4);float[] reference=new float[20];
                reference[0]=x;reference[1]=y;reference[2]=z;reference[4]=w1;reference[5]=w4;reference[6]=q.level;
                for(float v:reference)packets.putFloat(v);
                float w=(c&1)==0?w1:w4;if(w==0)zero++;else if(w==1)wet++;else partial++;
                if(q.packed.primitives()[3]==2)varying++;levels[q.level]++;
            }
            if(zero==0||wet==0||partial==0||varying==0)throw new IllegalStateException("Source capture lacks zero/wet/partial/varying controls");
            Files.write(root.resolve("water-layer-primitives.bin"),packets.array());
            int[] words=RtWaterFieldImage.pack(snapshot.tiles());ByteBuffer image=ByteBuffer.allocate(words.length*4).order(ByteOrder.LITTLE_ENDIAN);
            for(int word:words)image.putInt(word);
            for(String name:List.of("water-field-current.bin","water-field-previous.bin","water-field-full-cell.bin"))Files.write(root.resolve(name),image.array());
            var info=new LinkedHashMap<String,Object>();info.put("kind","actual-provider-mips");info.put("cases",CASES);info.put("anchorY",ANCHOR_Y);
            info.put("levels",levels);info.put("sourceQuads",quads.size());info.put("varyingCases",varying);info.put("zeroCases",zero);info.put("wetCases",wet);info.put("partialCases",partial);
            info.put("tiles",snapshot.tiles().size());info.put("epoch",snapshot.epoch());info.put("revision",snapshot.revision());
            int known=0,unknown=0,knownZero=0;
            for(var tile:snapshot.tiles())for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
                if(tile.known(x,z)){known++;if(tile.clearance(x,z)==0)knownZero++;}else unknown++;
            }
            if(unknown==0||knownZero==0||known==0)throw new IllegalStateException("Missing explicit known/unknown/covered source controls");
            info.put("knownNodes",known);info.put("unknownNodes",unknown);info.put("knownZeroNodes",knownZero);info.put("unknownAnchorXZ",65536);
            info.put("scope","Actual provider mesh and source export; internal diagonals; no inter-quad, game optics or FPS acceptance");
            Files.writeString(root.resolve("capture.json"),new GsonBuilder().setPrettyPrinting().create().toJson(info));written=true;
            System.out.println("GYSF_WATER_SOURCE: PASS cases="+CASES+" varying="+varying+" zero="+zero+" wet="+wet+" partial="+partial+" tiles="+snapshot.tiles().size());
        } catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
    }
    public static void verify(){if(ENABLED&&!written)throw new IllegalStateException("Water source capture incomplete");}
}
