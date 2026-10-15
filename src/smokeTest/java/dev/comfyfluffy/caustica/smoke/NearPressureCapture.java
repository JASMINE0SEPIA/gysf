package dev.comfyfluffy.caustica.smoke;

import dev.comfyfluffy.caustica.rt.*;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import dev.comfyfluffy.caustica.rt.terrain.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.lwjgl.vulkan.VK10;
import java.nio.file.*;
import java.io.*;
import java.util.*;

/** Real unused device allocations provoke the driver's working-set policy in the isolated world. */
final class NearPressureCapture {
    private final ArrayList<RtBuffer> held=new ArrayList<>();
    private final StringBuilder csv=new StringBuilder("elapsedMs,radius,nearInstances,lodSections,processBytes,budgetBytes,rtBytes,heldBytes\n");
    private long started,nextStep,released,heldBytes,initialRt,stableRecoveryAt;
    private int initialRadius,minRadius,initialInstances,minInstances;
    private boolean sawDriverPressure,sawRetirement;
    private boolean capturedPressure;

    boolean tick(Minecraft client,long now,Path output) {
        var ctx=RtContext.currentOrNull();var near=RtTerrain.currentOrNull();
        if(ctx==null || near==null)throw new IllegalStateException("Near pressure renderer missing");
        var heap=ctx.memoryBudget();
        int radius=PerformanceCapture.nearRadius(near),instances=near.staticInstances().size();
        if(started==0) {
            if(!heap.driverReported() || radius<=4 || RtDistantHorizonsTerrain.INSTANCE.residentSectionCount()==0)
                throw new IllegalStateException("Near pressure needs a driver budget, wide near window and published LOD");
            started=now;initialRadius=minRadius=radius;initialInstances=minInstances=instances;initialRt=heap.rtAllocationBytes();
            System.out.println("GYSF_NEAR_PRESSURE: begin radius="+radius+" instances="+instances+" RTbytes="+initialRt);
            capture(client,output,"before-pressure");
        }
        minRadius=Math.min(minRadius,radius);minInstances=Math.min(minInstances,instances);
        sawDriverPressure|=heap.allocatorHeadroomBytes()<heap.reserveBytes();
        sawRetirement|=radius<initialRadius && instances<initialInstances && heap.rtAllocationBytes()-heldBytes<initialRt*9/10;
        if(!capturedPressure && now-started>=30_000_000_000L) {capture(client,output,"under-pressure");capturedPressure=true;}
        if(now>=nextStep) {
            nextStep=now+500_000_000L;
            csv.append((now-started)/1e6).append(',').append(radius).append(',').append(instances).append(',')
                    .append(RtDistantHorizonsTerrain.INSTANCE.residentSectionCount()).append(',').append(heap.usageBytes()).append(',')
                    .append(heap.budgetBytes()).append(',').append(heap.rtAllocationBytes()).append(',').append(heldBytes).append('\n');
            // Allocate slowly, leave 128 MiB of actual reported headroom, cap at 3 GiB. Buffers are
            // never submitted to the GPU and can be freed immediately after the hold interval.
            if(released==0 && !sawDriverPressure && heldBytes<3072*RtLodMemory.MIB) {
                long bytes=Math.min(128*RtLodMemory.MIB,heap.availableBytes()-128*RtLodMemory.MIB);
                if(bytes>0) {
                    held.add(ctx.createBuffer(bytes,VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,false,"smoke-only VRAM pressure"));
                    heldBytes+=bytes;
                }
            }
        }
        if(released==0 && now-started>=45_000_000_000L) {
            for(var buffer:held)buffer.destroy();held.clear();
            System.out.println("GYSF_NEAR_PRESSURE: released "+heldBytes+" bytes; radius="+radius+" min="+minRadius+" retired="+sawRetirement);
            heldBytes=0;released=now;
            if(!sawDriverPressure || !sawRetirement || minRadius>=initialRadius || minInstances>=initialInstances)
                throw new IllegalStateException("Real device pressure did not contract exact-terrain residency");
        }
        // A radius change queues missing sections before their workers are dispatched. Require a
        // quiet interval after regrowth so we do not exit on that first, still-unfilled tick.
        boolean recovered=released!=0 && radius==initialRadius
                && !near.hasPendingBuilds() && !RtDistantHorizonsTerrain.INSTANCE.hasPendingBuilds();
        if(!recovered)stableRecoveryAt=0;
        else if(stableRecoveryAt==0)stableRecoveryAt=now;
        if(recovered && now-released>=60_000_000_000L && now-stableRecoveryAt>=10_000_000_000L) {
            if(RtDistantHorizonsTerrain.INSTANCE.residentSectionCount()==0)
                throw new IllegalStateException("No published LOD after recovery");
            try {Files.writeString(output.resolve("near-pressure.csv"),csv);}
            catch(IOException e){throw new UncheckedIOException(e);}
            if(NearOwnershipSmokeProbe.checks()<30)throw new IllegalStateException("Too few published ownership checks");
            capture(client,output,"after-recovery");
            System.out.println("GYSF_NEAR_PRESSURE: PASS initial="+initialRadius+" minimum="+minRadius+" recovered="+radius
                    +" instances="+initialInstances+" -> "+minInstances+" -> "+instances+" retirementObserved="+sawRetirement
                    +" ownershipChecks="+NearOwnershipSmokeProbe.checks());
            return true;
        }
        if(released!=0 && now-released>=180_000_000_000L)
            throw new IllegalStateException("Near working set or LOD did not settle and recover within three minutes");
        return false;
    }

    private static void capture(Minecraft client,Path output,String name) {
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(),image->{
            try{image.writeToFile(output.resolve(name+".png"));}
            catch(IOException e){throw new UncheckedIOException(e);}finally{image.close();}
        });
    }
}
