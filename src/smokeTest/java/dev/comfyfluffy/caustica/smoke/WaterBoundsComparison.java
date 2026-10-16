package dev.comfyfluffy.caustica.smoke;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.rt.*;
import dev.comfyfluffy.caustica.rt.accel.*;
import dev.comfyfluffy.caustica.rt.terrain.*;
import java.nio.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.lwjgl.system.*;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;

/** Smoke-only matched physical surfaces with wide/tight water BLAS bounds. No shipping geometry change. */
public final class WaterBoundsComparison {
    public static final boolean ENABLED=Boolean.getBoolean("gysf.smoke.compareWaterBounds");
    private static final int[] SEQUENCE=Arrays.stream(System.getProperty("gysf.smoke.waterBoundsSequence","0,1,1,0").split(","))
            .mapToInt(Integer::parseInt).toArray();
    private record Input(float[] bounds,byte[] geometryHash,int triangles,int tops) {}
    private static final ThreadLocal<Input> packed=new ThreadLocal<>();
    private static final Map<Long,Input> captured=new ConcurrentHashMap<>();
    private static final Map<Long,RtAccel> alternate=new ConcurrentHashMap<>();
    private static final Set<Long> seen=new HashSet<>();
    private static volatile boolean prepared,building,invalidated;
    private static long observedFrame=-1;
    private static int variant,selections,observedVariant=-1;
    private static String sourceHash="none";

    public static void configure() {
        if(!ENABLED)return;
        CausticaConfig.Rt.Composite.WATER_WAVES.set(true);
        CausticaConfig.Rt.Composite.WATER_WAVE_STRENGTH.set(1f);
        CausticaConfig.Rt.Composite.WATER_WAVE_DETAIL.set(7);
    }

    public static void packed(RtWaterGeometry.Packed p) {
        if(!ENABLED)return;
        float[] narrow=p.aabbs().clone(),primitives=p.primitives();
        for(int triangle=0;triangle<p.triangleCount();triangle++) {
            int v=triangle*RtWaterGeometry.PRIMITIVE_FLOATS;
            if(primitives[v+3]==0)continue;
            float top=Math.max(primitives[v+1],Math.max(primitives[v+5],primitives[v+9]));
            narrow[triangle*6+4]=Math.nextUp(top+0.55f+0.0001f);
            if(narrow[triangle*6+4]>p.aabbs()[triangle*6+4])throw new IllegalStateException("Tight bounds grew");
        }
        var digest=digest();digest.update(bytes(primitives));digest.update(bytes(p.aabbs()));
        packed.set(new Input(narrow,digest.digest(),p.triangleCount(),p.topTriangles()));
    }

    public static void captured(RtAccel.PreparedBlas blas,int count) {
        if(!ENABLED||building)return;
        Input input=packed.get();packed.remove();
        if(input==null||input.triangles()!=count)throw new IllegalStateException("Water bounds capture lost its source pack");
        if(captured.putIfAbsent(blas.accel.deviceAddress,input)!=null)throw new IllegalStateException("Duplicate live water BLAS address");
    }

    public static void retired(long address) {
        if(!ENABLED)return;
        captured.remove(address);
        if(prepared&&alternate.containsKey(address))invalidated=true;
    }

    public static void prepare() {
        if(!ENABLED||prepared)return;
        if(!Boolean.getBoolean("gysf.smoke.performance")||!RtWaterGeometry.runtimeEnabled()||WaterShaderComparison.ENABLED)
            throw new IllegalStateException("Bounds comparison requires isolated finite-water performance mode");
        checkSettings();
        var ctx=RtContext.currentOrNull();ctx.waitIdle();
        var near=RtTerrain.currentOrNull();
        var instances=RtDistantHorizonsTerrain.INSTANCE.appendInstances(near.staticInstances(),near.blockX,near.blockY,near.blockZ);
        Set<Long> active=new TreeSet<>();
        for(var instance:instances)if(instance.waterBlasDeviceAddress()!=0)active.add(instance.waterBlasDeviceAddress());
        if(active.isEmpty())throw new IllegalStateException("No water BLAS to compare");
        var source=digest();long triangles=0,tops=0;
        building=true;
        try {
            for(long address:active) {
                Input input=captured.get(address);
                if(input==null)throw new IllegalStateException("Missing captured resident water geometry");
                source.update(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(address).array());
                source.update(input.geometryHash());triangles+=input.triangles();tops+=input.tops();
                var bounds=ctx.createBuffer((long)input.bounds().length*4,VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                        true,"smoke tight water bounds");
                RtAccel.PreparedBlas op=null;
                try {
                    MemoryUtil.memFloatBuffer(bounds.mapped,input.bounds().length).put(input.bounds());bounds.flush();
                    op=RtAccel.prepareWaterBlas(ctx,bounds,input.triangles(),"smoke tight water BLAS");
                    alternate.put(address,op.accel);
                    var build=op;
                    ctx.submitSync(cmd->{
                        try(var stack=MemoryStack.stackPush()) {
                            var barrier=VkMemoryBarrier.calloc(1,stack);
                            barrier.get(0).sType$Default().srcAccessMask(VK_ACCESS_HOST_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
                            vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_HOST_BIT,VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,0,barrier,null,null);
                            RtAccel.recordBlasBuilds(ctx,cmd,List.of(build));
                            barrier.get(0).srcAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR).dstAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
                            vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                                    VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR|VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,0,barrier,null,null);
                        }
                    });
                } catch(Throwable failure) {ctx.waitIdle();throw failure;}
                finally {if(op!=null)RtAccel.freeBlasScratch(List.of(op));bounds.destroy();}
            }
            if(tops==0)throw new IllegalStateException("No displaced water tops in bounds comparison");
            sourceHash=HexFormat.of().formatHex(source.digest());prepared=true;
            System.out.println("GYSF_BOUNDS_COMPARE: ready children="+active.size()+" triangles="+triangles+" tops="+tops
                    +" source="+sourceHash+" wide=2.2 tight=0.55");
        } finally {building=false;}
    }

    public static void select(int phase,int phases) {
        if(!ENABLED)return;
        if(!prepared||SEQUENCE.length!=1&&SEQUENCE.length!=phases)throw new IllegalStateException("Invalid bounds comparison phase setup");
        int next=SEQUENCE[SEQUENCE.length==1?0:phase];
        if(next<0||next>1)throw new IllegalArgumentException("Invalid bounds variant");
        RtContext.currentOrNull().waitIdle();variant=next;selections++;
        System.out.println("GYSF_BOUNDS_COMPARE: select phase="+phase+" variant="+variant+" source="+sourceHash);
    }

    public static long address(long original) {
        if(!prepared)return original;
        RtAccel replacement=alternate.get(original);
        if(replacement==null)throw new IllegalStateException("Unmatched water child entered the comparison");
        long frame=RtComposite.frameCounter();
        if(observedFrame!=frame){observedFrame=frame;observedVariant=variant;seen.clear();}
        if(observedVariant!=variant)throw new IllegalStateException("Water bounds variant changed while packing one TLAS");
        seen.add(original);
        return variant==0?original:replacement.deviceAddress;
    }

    public static String measuredSource(long frame) {
        if(!ENABLED)return "none";
        checkSettings();
        if(!prepared||invalidated||observedFrame!=frame||observedVariant!=variant||!seen.equals(alternate.keySet()))
            throw new IllegalStateException("Bounds comparison did not observe the complete immutable water scene");
        return sourceHash;
    }
    public static int variant(){return ENABLED?variant:-1;}
    public static int children(){return alternate.size();}

    public static void destroy() {
        if(!ENABLED)return;
        prepared=false;
        var ctx=RtContext.currentOrNull();if(ctx!=null)ctx.waitIdle();
        int count=alternate.size();
        for(var accel:alternate.values())accel.destroy();
        alternate.clear();captured.clear();seen.clear();packed.remove();
        System.out.println("GYSF_BOUNDS_COMPARE: complete selections="+selections+" released="+count+" liveAlternates=0");
    }

    private static void checkSettings() {
        if(!CausticaConfig.Rt.Composite.WATER_WAVES.value()||CausticaConfig.Rt.Composite.WATER_WAVE_STRENGTH.value()!=1f
                ||CausticaConfig.Rt.Composite.WATER_WAVE_SPEED.value()!=0f||CausticaConfig.Rt.Composite.WATER_WAVE_DETAIL.value()!=7)
            throw new IllegalStateException("Bounds diagnostic requires frozen waves at strength 1");
    }
    private static byte[] bytes(float[] data) {
        var buffer=ByteBuffer.allocate(data.length*4).order(ByteOrder.LITTLE_ENDIAN);buffer.asFloatBuffer().put(data);return buffer.array();
    }
    private static MessageDigest digest() {
        try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
