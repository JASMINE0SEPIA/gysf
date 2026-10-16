package dev.comfyfluffy.caustica.rt.terrain;

import dev.comfyfluffy.caustica.compat.VoxyWaterDomain;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtGpuExecutor;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;

/** Bounded render-thread field residency. Immutable generations retain their exact last graphics use. */
public final class RtWaterFieldGpu {
    private static final long POLL_NANOS=100_000_000L;
    // Six graphics push slots plus current/previous: no unbounded retirement/allocation queue.
    private final Slot[] slots=new Slot[8];
    private Slot current;
    private int cursor;
    private long nextPoll,epoch=Long.MIN_VALUE;
    private java.lang.ref.WeakReference<net.minecraft.client.multiplayer.ClientLevel> levelIdentity=new java.lang.ref.WeakReference<>(null);
    private int requested,available,pending;
    private java.util.List<VoxyWaterDomain.Tile> lastTiles=java.util.List.of();
    private long uploads,liveBytes,peakBytes,knownNodes;
    private static final class Slot {
        RtBuffer buffer;
        final RtGpuExecutor.TrackedGraphicsUse use=new RtGpuExecutor.TrackedGraphicsUse();
    }
    public record Frame(long current,long previous) {}
    public record Statistics(long uploads,long liveBytes,long peakBytes,long knownNodes,int requested,int available,int pending) {}
    public Statistics statistics() {return new Statistics(uploads,liveBytes,peakBytes,knownNodes,requested,available,pending);}

    /** Read-only diagnostic copy while the render thread owns an immutable generation. */
    public byte[] copyImage(long address) {
        if(address==0)return new byte[0];
        for(var slot:slots)if(slot!=null&&slot.buffer!=null&&slot.buffer.deviceAddress==address) {
            int words=MemoryUtil.memGetInt(slot.buffer.mapped+12);
            if(words<4||(long)words*4>slot.buffer.size)throw new IllegalStateException("Invalid live water field image size");
            byte[] bytes=new byte[words*4];MemoryUtil.memByteBuffer(slot.buffer.mapped,bytes.length).get(bytes);return bytes;
        }
        throw new IllegalStateException("Diagnostic water field is not resident");
    }

    public Frame frame(RtContext ctx,RtTerrain near,double camX,double camY,double camZ,
                       RtGpuExecutor.GraphicsUse use,RtGpuExecutor.GraphicsUseWaiter waiter) {
        if(!RtWaterGeometry.runtimeEnabled()||!VoxyWaterDomain.supported())return new Frame(0,0);
        var level=net.minecraft.client.Minecraft.getInstance().level;
        if(level==null)return new Frame(0,0);
        boolean worldChanged=levelIdentity.get()!=level;
        if(worldChanged){levelIdentity=new java.lang.ref.WeakReference<>(level);nextPoll=0;}
        Slot previous=current;
        long now=System.nanoTime();
        if(current==null||now>=nextPoll) {
            nextPoll=now+POLL_NANOS;
            var desired=new RtWaterFieldRequests(RtWaterFieldImage.MAX_TILES,camX,camY,camZ,
                    level.getMinY(),Math.addExact(level.getMinY(),level.getHeight()));
            near.collectWaterDomainTiles(desired);
            RtDistantHorizonsTerrain.INSTANCE.collectWaterDomainTiles(desired);
            int[] xyz=desired.triples();int count=xyz.length/3;
            var snapshot=VoxyWaterDomain.poll(xyz);
            boolean reset=worldChanged||(snapshot==null ? epoch!=Long.MIN_VALUE : snapshot.epoch()!=epoch);
            epoch=snapshot==null?Long.MIN_VALUE:snapshot.epoch();
            requested=count;available=snapshot==null?0:snapshot.tiles().size();pending=snapshot==null?0:snapshot.pending();
            var tiles=snapshot==null?java.util.List.<VoxyWaterDomain.Tile>of():snapshot.tiles();
            // Stable order avoids uploads caused solely by provider iteration order.
            tiles=new java.util.ArrayList<>(tiles);tiles.sort(java.util.Comparator.comparingLong(VoxyWaterDomain.Tile::key));
            boolean changed=reset||current==null||tiles.size()!=lastTiles.size();
            for(int i=0;!changed&&i<tiles.size();i++)
                changed=tiles.get(i).key()!=lastTiles.get(i).key()||tiles.get(i).revision()!=lastTiles.get(i).revision();
            if(changed) {
                int[] next=RtWaterFieldImage.pack(tiles);
                Slot target=slots[cursor];
                if(target==null)slots[cursor]=target=new Slot();
                cursor=(cursor+1)%slots.length;
                if(target==current)throw new IllegalStateException("Water field ring reused current generation");
                waiter.await(target.use);
                long bytes=(long)next.length*4;
                if(target.buffer==null||target.buffer.size<bytes) {
                    if(target.buffer!=null){liveBytes-=target.buffer.size;target.buffer.destroy();target.buffer=null;}
                    target.buffer=ctx.createBuffer(bytes,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,true,"canonical water field");
                    liveBytes+=target.buffer.size;peakBytes=Math.max(peakBytes,liveBytes);
                }
                MemoryUtil.memIntBuffer(target.buffer.mapped,next.length).put(next);target.buffer.flush();
                current=target;lastTiles=java.util.List.copyOf(tiles);uploads++;
                knownNodes=0;for(var tile:tiles)for(long mask:tile.knownMask())knownNodes+=Long.bitCount(mask);
                if(reset)previous=current;
            }
        }
        if(previous==null)previous=current;
        current.use.mark(use);previous.use.mark(use);
        return new Frame(current.buffer.deviceAddress,previous.buffer.deviceAddress);
    }
    /** Caller has waited for device idle during RT shutdown. */
    public void destroy() {
        for(int i=0;i<slots.length;i++)if(slots[i]!=null) {
            if(slots[i].buffer!=null){liveBytes-=slots[i].buffer.size;slots[i].buffer.destroy();}slots[i]=null;
        }
        if(uploads!=0)dev.comfyfluffy.caustica.CausticaMod.LOGGER.info(
                "Water field GPU shutdown: live={} bytes, peak={} bytes, uploads={}",liveBytes,peakBytes,uploads);
        current=null;lastTiles=java.util.List.of();epoch=Long.MIN_VALUE;nextPoll=0;cursor=0;
        levelIdentity.clear();
    }
}
