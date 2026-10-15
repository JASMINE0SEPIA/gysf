package dev.comfyfluffy.caustica.smoke;

import dev.comfyfluffy.caustica.rt.*;
import dev.comfyfluffy.caustica.rt.terrain.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.*;
import org.lwjgl.vulkan.VK10;
import java.nio.file.*;
import java.io.*;

/** Diagnostic-only allocator/system comparison; never changes the production budgeting policy. */
final class MemoryDiagnostics {
    static final boolean ONLY=Boolean.getBoolean("gysf.smoke.memoryDiagnosticOnly");
    private static long nextSample,nextLog,idleAt;
    private static final StringBuilder csv=new StringBuilder("elapsedMs,driverUsage,driverBudget,rtHeapAlloc,rtBlocks,rtAlloc,rtUnusedBlocks,vmaUsage,vmaBudget,nearRadius,nearInstances,lodInstances,idleProbeDone\n");

    static boolean tick(long now,long started,Path output) {
        if(!ONLY)return false;
        var ctx=RtContext.currentOrNull();var near=RtTerrain.currentOrNull();
        if(ctx==null || near==null)return false;
        var heap=ctx.memoryBudget();
        if(now>=nextSample) {
            nextSample=now+500_000_000L;
            try(var stack=MemoryStack.stackPush()) {
                var field=RtContext.class.getDeclaredField("localHeapIndex");field.setAccessible(true);
                var budgets=VmaBudget.calloc(VK10.VK_MAX_MEMORY_HEAPS,stack);
                Vma.vmaGetHeapBudgets(ctx.vma(),budgets);
                var own=budgets.get(field.getInt(ctx));
                long blocks=own.statistics().blockBytes(),alloc=own.statistics().allocationBytes();
                csv.append((now-started)/1e6).append(',').append(heap.usageBytes()).append(',').append(heap.budgetBytes()).append(',')
                        .append(heap.rtAllocationBytes()).append(',').append(blocks).append(',').append(alloc).append(',').append(blocks-alloc).append(',')
                        .append(own.usage()).append(',').append(own.budget()).append(',').append(PerformanceCapture.nearRadius(near)).append(',')
                        .append(near.staticInstances().size()).append(',').append(RtDistantHorizonsTerrain.INSTANCE.residentSectionCount()).append(',').append(idleAt!=0).append('\n');
                Files.writeString(output.resolve("memory-diagnostics.csv"),csv);
                if(now>=nextLog) {
                    nextLog=now+5_000_000_000L;
                    System.out.println("GYSF_MEMORY_DIAG: driverMiB="+(heap.usageBytes()>>20)+" blocksMiB="+(blocks>>20)
                            +" allocationsMiB="+(alloc>>20)+" unusedBlockMiB="+((blocks-alloc)>>20)+" vmaUsageMiB="+(own.usage()>>20)
                            +" radius="+PerformanceCapture.nearRadius(near)+" idleProbeDone="+(idleAt!=0));
                }
            } catch(ReflectiveOperationException|IOException e){throw new IllegalStateException(e);}
        }
        if(idleAt==0 && now-started>=120_000_000_000L && heap.availableBytes()<heap.reserveBytes() && heap.rtAllocationBytes()<(1L<<30)) {
            ctx.waitIdle();idleAt=System.nanoTime();
            System.out.println("GYSF_MEMORY_DIAG: one diagnostic device-idle probe completed after contraction");
        }
        if(now-started>=180_000_000_000L && (idleAt==0 || now-idleAt>=30_000_000_000L)) {
            System.out.println("GYSF_MEMORY_DIAG: COMPLETE idleProbeDone="+(idleAt!=0)+"; this is allocator evidence, not a performance comparison");
            return true;
        }
        if(now-started>300_000_000_000L)throw new IllegalStateException("Memory diagnostic precondition was not reproduced");
        return false;
    }
}
