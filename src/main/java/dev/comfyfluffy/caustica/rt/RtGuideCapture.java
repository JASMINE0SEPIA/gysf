package dev.comfyfluffy.caustica.rt;

import com.google.gson.GsonBuilder;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;
import static org.lwjgl.vulkan.KHRSynchronization2.vkCmdPipelineBarrier2KHR;

/** Opt-in, bounded raw GPU diagnostic. Copies are excluded from performance measurements. */
public final class RtGuideCapture {
    public static final RtGuideCapture INSTANCE=new RtGuideCapture();
    private static final boolean ENABLED=Boolean.getBoolean("gysf.rt.captureGuides");
    private static final long MAX_PIXELS=2_097_152;
    public static boolean enabled(){return ENABLED;}
    public record Image(String name,String format,int bytes,RtImage image) {}
    private record Request(Path directory,Map<String,Object> context,CompletableFuture<Path> result) {}
    private record Region(String name,String format,long offset,long bytes) {}
    private final ArrayBlockingQueue<Request> requests=new ArrayBlockingQueue<>(1);
    private Flight flight;
    private long liveBytes,peakBytes,saved;
    private static final class Flight {
        final Request request;final int width,height;final long frame;
        final RtGpuExecutor.TrackedGraphicsUse use=new RtGpuExecutor.TrackedGraphicsUse();
        final RtBuffer surface;
        RtBuffer copies;List<Region> regions;Map<String,Object> metadata;
        byte[] currentField,previousField;
        boolean recorded,submitted;int age;
        Flight(Request r,int w,int h,long f,RtBuffer s){request=r;width=w;height=h;frame=f;surface=s;}
    }

    public CompletableFuture<Path> request(Path directory,Map<String,Object> context) {
        if(!ENABLED)throw new IllegalStateException("Guide readback is not enabled");
        if(Files.exists(directory.resolve("capture.json")))throw new IllegalArgumentException("Use a fresh capture directory");
        var result=new CompletableFuture<Path>();
        if(!requests.offer(new Request(directory.toAbsolutePath(),Map.copyOf(context),result)))
            throw new IllegalStateException("Guide readback request queue is full");
        return result;
    }

    /** Return a writable surface address for this frame only. Native buffers remain owned until completion. */
    public long begin(RtContext ctx,int width,int height,long frame,RtGpuExecutor.GraphicsUseWaiter waiter) {
        if(!ENABLED)return 0;
        if(flight!=null&&flight.submitted&&++flight.age>=6) {
            waiter.await(flight.use);
            finish();
        }
        if(flight==null) {
            Request request=requests.poll();
            if(request!=null) {
                long pixels=Math.multiplyExact((long)width,height);
                if(width<=0||height<=0||pixels>MAX_PIXELS)throw new IllegalArgumentException("Guide readback exceeds pixel budget");
                RtBuffer surface=ctx.createBuffer(pixels*64,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,true,"captured physical interfaces");
                liveBytes+=surface.size;peakBytes=Math.max(peakBytes,liveBytes);
                flight=new Flight(request,width,height,frame,surface);
            }
        }
        return flight!=null&&!flight.recorded?flight.surface.deviceAddress:0;
    }

    public void record(RtContext ctx,VkCommandBuffer cmd,MemoryStack stack,RtBuffer push,int pushBytes,
                       List<Image> images,Map<String,Object> metadata,byte[] currentField,byte[] previousField) {
        Flight f=flight;
        if(f==null||f.recorded)throw new IllegalStateException("Guide capture was not reserved for this frame");
        long cursor=0;var regions=new ArrayList<Region>();
        for(Image image:images) {
            if(image.image.width!=f.width||image.image.height!=f.height)throw new IllegalArgumentException("Guide image extent mismatch");
            cursor=align(cursor);long bytes=(long)f.width*f.height*image.bytes;
            regions.add(new Region(image.name,image.format,cursor,bytes));cursor+=bytes;
        }
        cursor=align(cursor);regions.add(new Region("world-push","bytes",cursor,pushBytes));cursor+=pushBytes;
        f.copies=ctx.createBuffer(cursor,VK_BUFFER_USAGE_TRANSFER_DST_BIT,true,"captured guide image copies");
        liveBytes+=f.copies.size;peakBytes=Math.max(peakBytes,liveBytes);
        f.regions=List.copyOf(regions);f.metadata=Map.copyOf(metadata);
        f.currentField=currentField;f.previousField=previousField;
        for(int i=0;i<images.size();i++) {
            var image=images.get(i).image;
            imageBarrier(cmd,stack,image,VK_IMAGE_LAYOUT_GENERAL,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,VK_ACCESS_2_MEMORY_WRITE_BIT|VK_ACCESS_2_MEMORY_READ_BIT,
                    VK_PIPELINE_STAGE_2_TRANSFER_BIT,VK_ACCESS_2_TRANSFER_READ_BIT);
            VkBufferImageCopy.Buffer copy=VkBufferImageCopy.calloc(1,stack);
            copy.get(0).bufferOffset(regions.get(i).offset).bufferRowLength(0).bufferImageHeight(0);
            copy.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            copy.get(0).imageExtent().set(f.width,f.height,1);
            vkCmdCopyImageToBuffer(cmd,image.image,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,f.copies.handle,copy);
            imageBarrier(cmd,stack,image,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,VK_IMAGE_LAYOUT_GENERAL,
                    VK_PIPELINE_STAGE_2_TRANSFER_BIT,VK_ACCESS_2_TRANSFER_READ_BIT,
                    VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,VK_ACCESS_2_MEMORY_WRITE_BIT|VK_ACCESS_2_MEMORY_READ_BIT);
        }
        // The camera-medium stage writes WorldPush.waterCamera. Capture the actual GPU tuple as well.
        memoryBarrier(cmd,stack,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,VK_ACCESS_2_MEMORY_WRITE_BIT,
                VK_PIPELINE_STAGE_2_TRANSFER_BIT,VK_ACCESS_2_TRANSFER_READ_BIT);
        VkBufferCopy.Buffer copy=VkBufferCopy.calloc(1,stack);
        copy.get(0).srcOffset(0).dstOffset(regions.getLast().offset).size(pushBytes);
        vkCmdCopyBuffer(cmd,push.handle,f.copies.handle,copy);
        memoryBarrier(cmd,stack,VK_PIPELINE_STAGE_2_TRANSFER_BIT|KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
                VK_ACCESS_2_TRANSFER_WRITE_BIT|VK_ACCESS_2_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_HOST_BIT,VK_ACCESS_2_HOST_READ_BIT);
        f.recorded=true;
    }
    public void submitted(RtGpuExecutor.GraphicsUse use) {
        if(flight!=null&&flight.recorded&&!flight.submitted){flight.use.mark(use);flight.submitted=true;}
    }
    private void finish() {
        Flight f=flight;
        try {
            f.surface.invalidate(0,f.surface.size);f.copies.invalidate(0,f.copies.size);
            Files.createDirectories(f.request.directory);
            write(f.request.directory.resolve("surface.bin"),f.surface.mapped,f.surface.size);
            var files=new LinkedHashMap<String,Object>();
            for(Region r:f.regions) {
                String name=r.name+".bin";write(f.request.directory.resolve(name),f.copies.mapped+r.offset,r.bytes);
                files.put(r.name,Map.of("file",name,"format",r.format,"bytes",r.bytes));
            }
            Files.write(f.request.directory.resolve("field-current.bin"),f.currentField);
            Files.write(f.request.directory.resolve("field-previous.bin"),f.previousField);
            var result=new LinkedHashMap<String,Object>();result.put("version",1);result.put("width",f.width);result.put("height",f.height);
            result.put("frame",f.frame);result.put("surfaceStride",64);result.put("surfaceFile","surface.bin");
            result.put("surfaceLanes",List.of("camRelativePosition.xyz, payloadFlags","normal.xyz, roughness",
                    "previousNormal.xyz, materialPlusOne (0=miss)","currentMinusPreviousDisplacement.xyz, distance"));
            result.put("files",files);result.put("frameState",f.metadata);result.put("request",f.request.context);
            var offsets=new TreeMap<String,Integer>();
            for(var field:WorldPushData.class.getFields())if(field.getName().endsWith("_OFFSET"))
                offsets.put(field.getName(),field.getInt(null));
            result.put("worldPushBytes",WorldPushData.BYTE_SIZE);result.put("worldPushOffsets",offsets);
            result.put("jitterSuppressed",true);result.put("byteOrder",java.nio.ByteOrder.nativeOrder().toString());
            Files.writeString(f.request.directory.resolve("capture.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result));
            saved++;f.request.result.complete(f.request.directory);
            CausticaMod.LOGGER.info("GYSF_GUIDE_CAPTURE: saved={} frame={} {}x{}",f.request.directory,f.frame,f.width,f.height);
        } catch(Exception e) {
            f.request.result.completeExceptionally(e);throw new IllegalStateException("Raw guide readback failed",e);
        } finally {release(f);flight=null;}
    }
    private static void write(Path path,long address,long bytes) throws java.io.IOException {
        byte[] data=new byte[Math.toIntExact(bytes)];MemoryUtil.memByteBuffer(address,data.length).get(data);Files.write(path,data);
    }
    private void release(Flight f) {
        liveBytes-=f.surface.size;f.surface.destroy();
        if(f.copies!=null){liveBytes-=f.copies.size;f.copies.destroy();}
    }
    private static long align(long value){return (value+15)&~15L;}
    private static void memoryBarrier(VkCommandBuffer cmd,MemoryStack stack,long srcStage,long srcAccess,long dstStage,long dstAccess) {
        VkMemoryBarrier2.Buffer barrier=VkMemoryBarrier2.calloc(1,stack);
        barrier.get(0).sType$Default().srcStageMask(srcStage).srcAccessMask(srcAccess).dstStageMask(dstStage).dstAccessMask(dstAccess);
        vkCmdPipelineBarrier2KHR(cmd,VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier));
    }
    private static void imageBarrier(VkCommandBuffer cmd,MemoryStack stack,RtImage image,int oldLayout,int newLayout,
                                     long srcStage,long srcAccess,long dstStage,long dstAccess) {
        VkImageMemoryBarrier2.Buffer barrier=VkImageMemoryBarrier2.calloc(1,stack);
        barrier.get(0).sType$Default().srcStageMask(srcStage).srcAccessMask(srcAccess).dstStageMask(dstStage).dstAccessMask(dstAccess)
                .oldLayout(oldLayout).newLayout(newLayout).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(image.image);
        barrier.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        vkCmdPipelineBarrier2KHR(cmd,VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barrier));
    }
    /** The caller's existing device-idle shutdown gate covers every possible in-flight capture. */
    public void destroy() {
        if(flight!=null){flight.request.result.completeExceptionally(new CancellationException("RT shutdown"));release(flight);flight=null;}
        Request pending;while((pending=requests.poll())!=null)pending.result.completeExceptionally(new CancellationException("RT shutdown"));
        if(ENABLED)CausticaMod.LOGGER.info("Guide readback shutdown: live={} bytes, peak={} bytes, captures={}",liveBytes,peakBytes,saved);
    }
}
