package dev.comfyfluffy.caustica.smoke;

import dev.comfyfluffy.caustica.rt.*;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import dev.comfyfluffy.caustica.rt.gen.WorldPushConstantsData;
import dev.comfyfluffy.caustica.rt.pipeline.RtPipeline;
import dev.comfyfluffy.caustica.rt.terrain.RtWaterGeometry;
import dev.comfyfluffy.caustica.smoke.mixin.PerformancePipelineAccessor;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Smoke-only A/B pipelines: immutable scene, descriptors and material epoch stay owned by the original. */
public final class WaterShaderComparison {
    private static final String BASELINE=System.getProperty("gysf.smoke.waterShaderBaseline","");
    private static final String HIT_BASELINE=System.getProperty("gysf.smoke.waterHitShaderBaseline","");
    private static final String INTERSECTION="/caustica/rt/world_water.rint.spv",HIT="/caustica/rt/world.rchit.spv";
    public static final boolean ENABLED=!BASELINE.isBlank();
    private static final int[] SEQUENCE=Arrays.stream(System.getProperty("gysf.smoke.waterShaderSequence","0").split(","))
            .mapToInt(Integer::parseInt).toArray();
    private static RtPipeline original,alternate;
    private static boolean creatingAlternate;
    private record Module(byte[] bytes,String hash) {}
    private static final Map<String,Module> baselines=new HashMap<>();
    private static final Map<String,String> candidates=new HashMap<>();
    private static int variant,switches,pipelineSeen=-1,sbtSeen=-1;
    private static long observedFrame=-1;

    public static InputStream shaderStream(Class<?> loader,String name) {
        String path=name.equals(INTERSECTION)?BASELINE:name.equals(HIT)?HIT_BASELINE:"";
        if(!ENABLED || path.isBlank())return loader.getResourceAsStream(name);
        if(!Boolean.getBoolean("gysf.smoke.performance") || !RtWaterGeometry.runtimeEnabled())
            throw new IllegalStateException("Water shader comparison requires finite-water performance mode");
        try {
            Module baseline=baselines.get(name);
            if(baseline==null) {
                byte[] bytes=Files.readAllBytes(Path.of(path));
                if(bytes.length<20||bytes.length%4!=0||java.nio.ByteBuffer.wrap(bytes)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt()!=0x07230203)
                    throw new IllegalArgumentException("Invalid baseline SPIR-V");
                baseline=new Module(bytes,hash(bytes));baselines.put(name,baseline);
            }
            if(!creatingAlternate)return new ByteArrayInputStream(baseline.bytes());
            try(var input=loader.getResourceAsStream(name)) {
                if(input==null)throw new IllegalStateException("Candidate water shader is missing");
                byte[] bytes=input.readAllBytes();String candidate=hash(bytes);candidates.put(name,candidate);
                if(name.equals(INTERSECTION)&&candidate.equals(baseline.hash()))throw new IllegalStateException("A/B water shaders are identical");
                return new ByteArrayInputStream(bytes);
            }
        } catch(IOException e){throw new UncheckedIOException(e);}
    }

    public static void prepare() {
        if(!ENABLED || alternate!=null)return;
        original=(RtPipeline)field(RtComposite.INSTANCE,"worldPipeline");
        if(original==null)return;
        var ctx=RtContext.currentOrNull();ctx.waitIdle();
        creatingAlternate=true;
        try {
            int guides=(int)field(RtComposite.INSTANCE,"GUIDE_COUNT");
            int bindless=(int)field(RtComposite.INSTANCE,"bindlessTextureCapacity");
            alternate=RtPipeline.create(ctx,new String[]{RtDeviceBringup.worldPrimaryRaygenShader(),
                            RtDeviceBringup.worldRaygenShader(),"world_water_camera.rgen.spv"},
                    new String[]{"world.rmiss.spv","world_guide.rmiss.spv"},"world.rchit.spv","world.rahit.spv",
                    WorldPushConstantsData.BYTE_SIZE,true,guides,bindless,true,"world_water.rint.spv");
            // Vulkan pipeline-layout compatibility uses identical definitions, not identical handles.
            // Both factories receive exactly the same descriptor/PC/SBT shape. Only executable+SBT switch.
            for(String key:List.of("sbtStride","raygenCount","missCount","hitGroupCount","pushConstantSize",
                    "pushConstantStages","firstExtraBinding","skyAtlasBinding"))
                if(!field(original,key).equals(field(alternate,key)))throw new IllegalStateException("A/B layout mismatch: "+key);
            System.out.println("GYSF_SHADER_COMPARE: ready baseline="+baselines.get(INTERSECTION).hash()+" candidate="+candidates.get(INTERSECTION)
                    +" hitBaseline="+moduleFingerprint(HIT,0)+" hitCandidate="+moduleFingerprint(HIT,1));
        } finally {creatingAlternate=false;}
    }

    public static void select(int phase,int count) {
        if(!ENABLED)return;
        if(alternate==null)throw new IllegalStateException("Alternate water pipeline was not prepared");
        if(SEQUENCE.length!=1 && SEQUENCE.length!=count)throw new IllegalArgumentException("Shader sequence count must match phases");
        int next=SEQUENCE[SEQUENCE.length==1?0:phase];
        if(next<0||next>1)throw new IllegalArgumentException("Invalid water shader variant");
        RtContext.currentOrNull().waitIdle();variant=next;switches++;
        System.out.println("GYSF_SHADER_COMPARE: select phase="+phase+" variant="+variant+" shader="+fingerprint());
    }

    public static long pipeline(RtPipeline owner,long handle) {
        if(!ENABLED || owner!=original || alternate==null)return handle;
        observe(true);
        return variant==0?handle:((PerformancePipelineAccessor)(Object)alternate).gysf$pipelineHandle();
    }
    public static RtBuffer sbt(RtPipeline owner,RtBuffer buffer) {
        if(!ENABLED || owner!=original || alternate==null)return buffer;
        observe(false);
        return variant==0?buffer:((PerformancePipelineAccessor)(Object)alternate).gysf$sbtBuffer();
    }
    private static void observe(boolean pipeline) {
        long frame=RtComposite.frameCounter();
        if(frame!=observedFrame){observedFrame=frame;pipelineSeen=sbtSeen=-1;}
        if((pipelineSeen>=0&&pipelineSeen!=variant)||(sbtSeen>=0&&sbtSeen!=variant))
            throw new IllegalStateException("Water shader changed during command recording");
        if(pipeline)pipelineSeen=variant;else sbtSeen=variant;
    }
    public static String measuredFingerprint(long frame) {
        if(!ENABLED)return "default";
        if(observedFrame!=frame||pipelineSeen!=variant||sbtSeen!=variant)
            throw new IllegalStateException("Actual pipeline/SBT selection was not observed for measured frame");
        return fingerprint();
    }
    public static String measuredHitFingerprint(long frame) {
        measuredFingerprint(frame);
        return moduleFingerprint(HIT,variant);
    }
    private static String moduleFingerprint(String name,int selected) {
        if(name.equals(HIT)&&HIT_BASELINE.isBlank())return "shared";
        String value=selected==0?baselines.get(name).hash():candidates.get(name);
        if(value==null)throw new IllegalStateException("Compared shader was not loaded: "+name);
        return value;
    }
    private static String fingerprint(){return moduleFingerprint(INTERSECTION,variant);}

    public static void destroy() {
        if(alternate==null)return;
        RtContext.currentOrNull().waitIdle();alternate.destroy();alternate=null;original=null;
        System.out.println("GYSF_SHADER_COMPARE: complete switches="+switches+" liveAlternates=0");
    }
    private static Object field(Object owner,String name) {
        try {var field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);}
        catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }
    private static String hash(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
