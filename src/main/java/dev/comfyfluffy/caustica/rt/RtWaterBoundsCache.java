package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.accel.*;
import dev.comfyfluffy.caustica.rt.terrain.RtWaterGeometry;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.lwjgl.system.*;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;

/** Optional immutable water BLAS cache. The original maximum-height BLAS is always a valid fallback. */
public final class RtWaterBoundsCache implements AutoCloseable {
    private static final long MIB=1L<<20;
    private static final boolean REQUESTED=RtWaterGeometry.runtimeEnabled()
            &&Boolean.parseBoolean(System.getProperty("gysf.rt.waterBoundsCache","true"));
    private final long limit=Math.clamp(Long.getLong("gysf.rt.waterBoundsCacheMiB",64L),0,256)*MIB;
    private final long inputLimit=Math.clamp(Long.getLong("gysf.rt.waterBoundsInputMiB",64L),0,256)*MIB;
    private final RtContext ctx;
    private final RtLodMemory memory=new RtLodMemory();
    private final AtomicLong inputBytes=new AtomicLong();
    private final AtomicLong inputDeferrals=new AtomicLong();
    private final AtomicLong cancellations=new AtomicLong();
    private final Map<Long,Owner> owners=new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Owner> requests=new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Job> ready=new ConcurrentLinkedQueue<>();
    private final Set<Job> jobs=ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<Job> quarantine=new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Entry> retiring=new ConcurrentLinkedQueue<>();
    private volatile boolean closed;
    private volatile float target=RtWaterGeometry.MAX_DISPLACEMENT;
    private float required=RtWaterGeometry.MAX_DISPLACEMENT;
    private volatile long epoch;
    private long frame,backoff;
    private long published,stale,hits,fallbacks,eligibleMisses;
    private volatile Throwable failure;

    public record Statistics(boolean enabled,int sources,int ready,int pending,long inputBytes,long liveBytes,
                             long peakBytes,long published,long stale,long hits,long fallbacks,long eligibleMisses,long inputDeferrals,
                             long nativeDeferrals,long cancellations) {}

    RtWaterBoundsCache(RtContext ctx){this.ctx=ctx;}
    public boolean enabled(){return REQUESTED&&limit>0&&inputLimit>0;}

    /** The physical shader height remains untouched. Bounds round upward in quarter-strength bins. */
    public static float requiredHeight(float strength,boolean waves) {
        if(!waves)return 0;
        if(!Float.isFinite(strength))return RtWaterGeometry.MAX_DISPLACEMENT;
        return (RtWaterGeometry.MAX_DISPLACEMENT/4f)*Math.clamp(strength,0f,4f);
    }
    public static float targetHeight(float strength,boolean waves) {
        if(!waves)return 0;
        if(!Float.isFinite(strength))return RtWaterGeometry.MAX_DISPLACEMENT;
        float bucket=(float)Math.ceil((double)Math.clamp(strength,0f,4f)*4.0)*0.25f;
        return Math.min(RtWaterGeometry.MAX_DISPLACEMENT,(RtWaterGeometry.MAX_DISPLACEMENT/4f)*bucket);
    }

    /** Called by the section worker before the original AS is published. */
    public void register(RtAccel source,RtWaterGeometry.Packed packed) {
        if(!enabled()||closed||packed.topTriangles()==0)return;
        long bytes=((long)packed.aabbs().length+packed.triangleCount())*4;
        long live;
        do {live=inputBytes.get();if(bytes>inputLimit-live){inputDeferrals.incrementAndGet();return;}}
        while(!inputBytes.compareAndSet(live,live+bytes));
        boolean transferred=false;
        try {
            var input=RtWaterGeometry.boundsInput(packed);
            synchronized(this) {
                if(closed)return;
                Owner owner=new Owner(source.deviceAddress,input);
                if(owners.putIfAbsent(source.deviceAddress,owner)!=null)throw new IllegalStateException("Live water source address was reused");
                try {source.onDestroy(owner::close);}
                catch(Throwable t){owners.remove(source.deviceAddress,owner);throw t;}
                transferred=true;
            }
        } finally {if(!transferred)inputBytes.addAndGet(-bytes);}
    }

    /** Poll completed builds before beginGraphicsUse attaches their semaphore dependencies. */
    public void beginFrame(long frame,float strength,boolean waves) {
        this.frame=frame;hits=fallbacks=eligibleMisses=0;
        required=requiredHeight(strength,waves);target=targetHeight(strength,waves);
        if(!enabled()||closed)return;
        if(failure!=null)throw new IllegalStateException("Water bounds cache build failed",failure);
        var heap=ctx.memoryBudget();
        boolean pressured=heap.driverReported()&&heap.allocatorHeadroomBytes()<heap.reserveBytes()+limit;
        if(pressured&&frame>=backoff) {
            epoch++;backoff=frame+60;
            for(var owner:owners.values())owner.evict();
        }
        Job done;
        while((done=ready.poll())!=null)publish(done);
        drainRetirements();
        if(pressured||frame<backoff)return;
        int starts=0;
        for(int inspected=0;inspected<32&&starts<4&&jobs.size()<8;inspected++) {
            Owner owner=requests.poll();if(owner==null)break;
            synchronized(owner) {
                owner.queued=false;
                if(owner.closed||owner.pending!=null||owner.input==null||owner.lastSeen<frame-2
                        ||target>=RtWaterGeometry.MAX_DISPLACEMENT||owner.cached!=null&&owner.cached.height==target)continue;
                if(owner.retryAfter>frame){owner.request();continue;}
                if(schedule(owner))starts++;
            }
        }
    }

    /** Called only for an actual water child while writing the frame TLAS. */
    public long resolve(long original,RtGpuExecutor.GraphicsUse use) {
        if(!enabled()||closed)return original;
        Owner owner=owners.get(original);
        if(owner==null){fallbacks++;return original;}
        synchronized(owner) {
            if(owner.closed){fallbacks++;return original;}
            owner.lastSeen=frame;
            Entry cached=owner.cached;
            if(target<RtWaterGeometry.MAX_DISPLACEMENT&&(cached==null||cached.height!=target))owner.request();
            if(cached!=null&&cached.height>=required) {
                cached.lastUse=use;hits++;return cached.accel.deviceAddress;
            }
            fallbacks++;
            if(target<RtWaterGeometry.MAX_DISPLACEMENT)eligibleMisses++;
            return original;
        }
    }

    private boolean schedule(Owner owner) {
        Job job=new Job(owner,target,epoch);
        try(var scope=ctx.bufferAllocations(memory,limit)) {
            float[] data=owner.input.atHeight(job.height);
            job.bounds=ctx.createAsyncBuffer((long)data.length*4,VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                    true,"water bounds cache input");
            MemoryUtil.memFloatBuffer(job.bounds.mapped,data.length).put(data);job.bounds.flush();
            job.op=RtAccel.prepareWaterBlas(ctx,job.bounds,owner.input.count(),"water bounds cache BLAS");
            owner.pending=job;jobs.add(job);
            ctx.gpuExecutor().submit(()->closed||owner.closed||job.height!=target||job.epoch!=epoch,
                    cmd->{
                        try(var stack=MemoryStack.stackPush()) {
                            var barrier=VkMemoryBarrier.calloc(1,stack);
                            barrier.get(0).sType$Default().srcAccessMask(VK_ACCESS_HOST_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
                            vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_HOST_BIT,VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,0,barrier,null,null);
                            RtAccel.recordBlasBuilds(ctx,cmd,List.of(job.op));
                        }
                    },job::releaseInputs,(build,error)->finished(job,build,error));
            return true;
        } catch(RtLodMemory.Pressure pressure) {
            owner.retryAfter=frame+30;owner.request();job.destroyUnpublished();return false;
        } catch(Throwable t) {
            owner.pending=null;jobs.remove(job);job.destroyUnpublished();throw t;
        }
    }

    private void finished(Job job,RtGpuExecutor.Build build,Throwable error) {
        if(error!=null&&!(error instanceof CancellationException)) {
            // A failed native wait is not proof that submitted buffers are safe to destroy.
            failure=error;quarantine.add(job);return;
        }
        job.releaseInputs();jobs.remove(job);
        synchronized(job.owner) {
            if(error!=null||closed||job.owner.closed||job.height!=target||job.epoch!=epoch) {
                cancellations.incrementAndGet();
                if(job.owner.pending==job)job.owner.pending=null;
                job.destroyUnpublished();return;
            }
            job.build=build;job.completed=true;ready.add(job);
        }
    }

    private void publish(Job job) {
        Entry old=null;boolean accepted=false;
        synchronized(job.owner) {
            if(job.claimed)return;
            job.claimed=true;
            if(job.owner.pending==job)job.owner.pending=null;
            if(!closed&&!job.owner.closed&&owners.get(job.owner.address)==job.owner&&job.height==target&&job.epoch==epoch) {
                old=job.owner.cached;job.owner.cached=new Entry(job.op.accel,job.height);accepted=true;
            }
        }
        if(accepted){ctx.gpuExecutor().markPublished(job.build);published++;retire(old);}
        else {stale++;job.destroyUnpublished();}
    }

    private void retire(Entry entry) {
        if(entry==null)return;
        // Source destruction can itself run inside the executor's destroy-list iteration.
        // Hand this ownership back to the render thread instead of mutating that list recursively.
        retiring.add(entry);
    }
    private void drainRetirements() {
        Entry entry;
        while((entry=retiring.poll())!=null) {
            RtAccel accel=entry.accel;
            if(entry.lastUse==null)ctx.gpuExecutor().retireUnpublished(accel::destroy);
            else ctx.gpuExecutor().retireAfterGraphics(entry.lastUse,accel::destroy);
        }
    }

    public Statistics statistics() {
        int count=0;for(var owner:owners.values())if(owner.cached!=null)count++;
        return new Statistics(enabled(),owners.size(),count,jobs.size()+ready.size()+retiring.size(),inputBytes.get(),memory.liveBytes(),
                memory.peakBytes(),published,stale,hits,fallbacks,eligibleMisses,inputDeferrals.get(),memory.deferralCount(),cancellations.get());
    }

    @Override public void close() {
        synchronized(this){closed=true;epoch++;}
        for(var owner:owners.values())owner.close();
        requests.clear();
        Job job;while((job=ready.poll())!=null)publish(job);
        drainRetirements();
    }

    /** Called only after the executor has stopped and made the device idle. */
    public void afterExecutorShutdown() {
        Job job;while((job=ready.poll())!=null)publish(job);
        while((job=quarantine.poll())!=null){job.destroyUnpublished();jobs.remove(job);}
        Entry entry;while((entry=retiring.poll())!=null)entry.accel.destroy();
        var stats=statistics();
        CausticaMod.LOGGER.info("Water bounds cache shutdown: live={} bytes, peak={} bytes, inputs={} bytes, sources={}, pending={}, published={}",
                stats.liveBytes(),stats.peakBytes(),stats.inputBytes(),stats.sources(),stats.pending(),stats.published());
        if(stats.liveBytes()!=0||stats.inputBytes()!=0||stats.sources()!=0||stats.pending()!=0)
            throw new IllegalStateException("Water bounds cache did not release its resources");
    }

    private final class Owner {
        final long address;
        volatile boolean closed;
        RtWaterGeometry.BoundsInput input;
        volatile Entry cached;
        Job pending;
        boolean queued;
        long lastSeen,retryAfter;
        Owner(long address,RtWaterGeometry.BoundsInput input){this.address=address;this.input=input;}
        void request() {if(!queued&&pending==null&&!closed){queued=true;requests.add(this);}}
        void evict() {Entry old;synchronized(this){old=cached;cached=null;}retire(old);}
        void close() {
            Entry old;Job completed=null;
            synchronized(this) {
                if(closed)return;
                closed=true;owners.remove(address,this);
                inputBytes.addAndGet(-input.bytes());input=null;
                old=cached;cached=null;
                if(pending!=null&&pending.completed&&!pending.claimed) {
                    cancellations.incrementAndGet();
                    completed=pending;completed.claimed=true;pending=null;
                }
            }
            retire(old);if(completed!=null)completed.destroyUnpublished();
        }
    }
    private static final class Entry {
        final RtAccel accel;final float height;
        volatile RtGpuExecutor.GraphicsUse lastUse;
        Entry(RtAccel accel,float height){this.accel=accel;this.height=height;}
    }
    private final class Job {
        final Owner owner;final float height;final long epoch;
        RtBuffer bounds;RtAccel.PreparedBlas op;RtGpuExecutor.Build build;
        boolean inputsReleased,completed,claimed,destroyed;
        Job(Owner owner,float height,long epoch){this.owner=owner;this.height=height;this.epoch=epoch;}
        synchronized void releaseInputs() {
            if(inputsReleased)return;inputsReleased=true;
            if(op!=null)RtAccel.freeBlasScratch(List.of(op));
            if(bounds!=null)bounds.destroy();
        }
        synchronized void destroyUnpublished() {
            if(destroyed)return;destroyed=true;releaseInputs();if(op!=null)op.accel.destroy();
        }
    }
}
