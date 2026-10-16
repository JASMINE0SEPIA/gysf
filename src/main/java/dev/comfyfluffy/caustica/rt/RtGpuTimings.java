package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaConfig;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.io.*;
import java.nio.file.*;
import java.util.Locale;
import static org.lwjgl.vulkan.VK10.*;

/** Opt-in, sampled GPU intervals. Never waits for results or changes ordinary rendering settings. */
public final class RtGpuTimings {
    public static final RtGpuTimings INSTANCE=new RtGpuTimings();
    private static final String OUTPUT=System.getProperty("gysf.rt.gpuTimings","");
    private static final int SLOTS=32, QUERIES=9, SAMPLE_EVERY=8;
    private final Slot[] slots=new Slot[SLOTS];
    private RtContext owner;
    private long pool,saved,skipped;
    private double period;
    private long mask;
    private BufferedWriter csv;
    private Slot recording;
    private static final class Slot {
        final int first;
        final RtGpuExecutor.TrackedGraphicsUse use=new RtGpuExecutor.TrackedGraphicsUse();
        boolean pending;
        long frame;
        int preset,quality,rw,rh,dw,dh,next;
        String filter;
        Slot(int i){first=i*QUERIES;}
    }

    public void begin(RtContext ctx,VkCommandBuffer cmd,MemoryStack stack,long frame,
                      int rw,int rh,int dw,int dh,RtGpuExecutor.GraphicsUseWaiter waiter) {
        if(OUTPUT.isBlank())return;
        if(owner==null)initialize(ctx,stack);
        if(ctx!=owner)throw new IllegalStateException("GPU timing device changed without teardown");
        poll(waiter,stack);
        recording=null;
        if(frame%SAMPLE_EVERY!=0)return;
        for(var slot:slots)if(!slot.pending) {
            slot.frame=frame;slot.preset=CausticaConfig.Rt.DlssRr.PRESET.value();
            slot.quality=CausticaConfig.Rt.DlssRr.QUALITY.value();
            slot.rw=rw;slot.rh=rh;slot.dw=dw;slot.dh=dh;slot.next=1;slot.filter=null;
            recording=slot;
            vkCmdResetQueryPool(cmd,pool,slot.first,QUERIES);
            vkCmdWriteTimestamp(cmd,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,pool,slot.first);
            return;
        }
        skipped++;
    }

    public void mark(VkCommandBuffer cmd,int boundary) {
        if(recording==null)return;
        if(boundary!=recording.next++)throw new IllegalStateException("GPU timing boundary out of order");
        vkCmdWriteTimestamp(cmd,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,pool,recording.first+boundary);
    }

    public void filter(String filter){if(recording!=null)recording.filter=filter;}

    /** Called only after the encoder accepted the command buffer. */
    public void submitted(RtGpuExecutor.GraphicsUse use) {
        if(recording==null)return;
        if(recording.next!=QUERIES || recording.filter==null)throw new IllegalStateException("Incomplete GPU timing sample");
        recording.use.mark(use);recording.pending=true;recording=null;
    }

    private void initialize(RtContext ctx,MemoryStack stack) {
        var phys=ctx.vk().getPhysicalDevice();
        var props=VkPhysicalDeviceProperties.calloc(stack);
        vkGetPhysicalDeviceProperties(phys,props);
        var count=stack.callocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(phys,count,null);
        var queues=VkQueueFamilyProperties.calloc(count.get(0),stack);
        vkGetPhysicalDeviceQueueFamilyProperties(phys,count,queues);
        int bits=queues.get(ctx.device().graphicsQueue().queueFamilyIndex()).timestampValidBits();
        period=props.limits().timestampPeriod();
        if(bits==0 || !(period>0))throw new IllegalStateException("Graphics queue has no timestamp support");
        mask=bits==64?-1L:(1L<<bits)-1;
        try {
            Path file=Path.of(OUTPUT).toAbsolutePath();
            Files.createDirectories(file.getParent());
            csv=Files.newBufferedWriter(file,StandardOpenOption.CREATE_NEW);
            csv.write("rtFrame,preset,quality,renderWidth,renderHeight,displayWidth,displayHeight,filter,prepareMs,waterCameraMs,primaryMs,indirectMs,reconstructMs,exposureMs,displayMs,copyMs,totalGpuMs\n");
            var info=VkQueryPoolCreateInfo.calloc(stack).sType$Default().queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(SLOTS*QUERIES);
            var handle=stack.mallocLong(1);
            RtContext.check(vkCreateQueryPool(ctx.vk(),info,null,handle),"vkCreateQueryPool(GPU timings)");
            pool=handle.get(0);owner=ctx;
            for(int i=0;i<SLOTS;i++)slots[i]=new Slot(i);
            System.out.println("GYSF_GPU_TIMINGS: start timestampBits="+bits+" periodNs="+period+" sampleEvery="+SAMPLE_EVERY);
        } catch(IOException|RuntimeException e){
            if(pool!=0)vkDestroyQueryPool(ctx.vk(),pool,null);
            if(csv!=null)try {csv.close();}catch(IOException close){e.addSuppressed(close);}
            pool=0;owner=null;csv=null;
            if(e instanceof IOException io)throw new UncheckedIOException(io);
            throw (RuntimeException)e;
        }
    }

    private void poll(RtGpuExecutor.GraphicsUseWaiter waiter,MemoryStack stack) {
        var data=stack.mallocLong(QUERIES);
        for(var slot:slots)if(slot.pending && waiter.completed(slot.use)) {
            // Exact graphics completion proves this generation's GPU reset and all writes executed.
            // Availability alone could otherwise return the preceding generation's stale values.
            RtContext.check(vkGetQueryPoolResults(owner.vk(),pool,slot.first,QUERIES,data,Long.BYTES,VK_QUERY_RESULT_64_BIT),
                    "vkGetQueryPoolResults(GPU timings)");
            var line=new StringBuilder().append(slot.frame).append(',').append(slot.preset).append(',').append(slot.quality)
                    .append(',').append(slot.rw).append(',').append(slot.rh).append(',').append(slot.dw).append(',').append(slot.dh)
                    .append(',').append(slot.filter);
            for(int i=1;i<QUERIES;i++)line.append(',').append(milliseconds(data.get(i-1),data.get(i)));
            line.append(',').append(milliseconds(data.get(0),data.get(QUERIES-1))).append('\n');
            try {csv.write(line.toString());}catch(IOException e){throw new UncheckedIOException(e);}
            slot.pending=false;saved++;
        }
    }

    private String milliseconds(long a,long b) {
        return String.format(Locale.ROOT,"%.6f",((b-a)&mask)*period/1e6);
    }

    /** Device-idle teardown, matching the compositor's other GPU-owned resources. */
    public void destroy() {
        if(owner==null)return;
        try(MemoryStack stack=MemoryStack.stackPush()) {
            poll(owner.gpuExecutor().graphicsUseWaiter(),stack);
            for(var slot:slots)if(slot.pending)throw new IllegalStateException("GPU timing pool destroyed before graphics completion");
            vkDestroyQueryPool(owner.vk(),pool,null);
            try {csv.close();}catch(IOException e){throw new UncheckedIOException(e);}
            System.out.println("GYSF_GPU_TIMINGS: complete samples="+saved+" skipped="+skipped+" livePools=0");
        } finally {owner=null;pool=0;csv=null;recording=null;}
    }
}
