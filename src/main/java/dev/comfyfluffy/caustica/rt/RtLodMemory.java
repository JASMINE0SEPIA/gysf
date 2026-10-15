package dev.comfyfluffy.caustica.rt;

/** Native LOD working set, including staging, build scratch and geometry awaiting GPU retirement. */
public final class RtLodMemory {
    public static final long MIB = 1L << 20;
    public record Heap(long usageBytes, long budgetBytes, long rtAllocationBytes,
                       long observedLodBytes, boolean driverReported) {
        public long availableBytes() { return Math.max(0, budgetBytes - usageBytes); }
        public long reserveBytes() { return Math.min(1024*MIB, Math.max(256*MIB, budgetBytes/10)); }
    }
    public static final class Pressure extends RuntimeException {
        private final long requestedBytes;
        Pressure(String reason, long bytes, long live, long limit) {
            super(reason+": request="+bytes+", LOD live="+live+", limit="+limit, null, false, false);
            requestedBytes=bytes;
        }
        public long requestedBytes() { return requestedBytes; }
    }
    private long live, peak, deferrals;

    public static long limitBytes(long configuredMiB, long heapBudget) {
        return configuredMiB > 0 ? Math.clamp(configuredMiB, 1, 4096)*MIB
                : Math.clamp(heapBudget/4, 256*MIB, 2048*MIB);
    }
    public synchronized long liveBytes() { return live; }
    public synchronized long peakBytes() { return peak; }
    public synchronized long deferralCount() { return deferrals; }
    public synchronized Pressure nativeFailure(long bytes, long limit) {
        deferrals++;
        return new Pressure("LOD native allocator rejected device memory",bytes,live,limit);
    }

    public synchronized Ticket reserve(long bytes, long limit, Heap heap, boolean devicePreferred) {
        if (bytes < 0 || limit < 0) throw new IllegalArgumentException("Negative memory size");
        check(bytes, limit, heap, devicePreferred);
        live += bytes;
        peak = Math.max(peak, live);
        return new Ticket(bytes, limit, heap, devicePreferred);
    }
    private void check(long bytes, long limit, Heap heap, boolean devicePreferred) {
        String reason=rejection(bytes,limit,heap,devicePreferred);
        if(reason!=null) {
            deferrals++;
            throw new Pressure(reason,bytes,live,limit);
        }
    }
    /** Admission preview for deferred refreshes; neither allocates a ticket nor counts another failure. */
    public synchronized boolean canReserve(long bytes,long limit,Heap heap,boolean devicePreferred) {
        if(bytes<0 || limit<0)throw new IllegalArgumentException("Negative memory size");
        return rejection(bytes,limit,heap,devicePreferred)==null;
    }
    private String rejection(long bytes,long limit,Heap heap,boolean devicePreferred) {
        if (bytes > Math.max(0, limit-live)) {
            return "LOD native allocation budget reached";
        }
        // Cached driver readings already include allocations made by all allocators in this process.
        // Charge only LOD growth since that reading; never sum separate VMA heap usage estimates.
        long growth = Math.max(0, live-heap.observedLodBytes);
        long available = Math.max(0, heap.availableBytes()-growth);
        if (devicePreferred && heap.driverReported && bytes > Math.max(0, available-heap.reserveBytes())) {
            return "Driver heap headroom reserved for near rendering";
        }
        return null;
    }
    public final class Ticket implements AutoCloseable {
        private long bytes;
        private final long limit;
        private final Heap heap;
        private final boolean devicePreferred;
        private boolean closed;
        private Ticket(long bytes, long limit, Heap heap, boolean devicePreferred) {
            this.bytes=bytes; this.limit=limit; this.heap=heap; this.devicePreferred=devicePreferred;
        }
        /** Replace the requested size with VMA's actual allocation size, including alignment padding. */
        public void commit(long actualBytes) {
            synchronized (RtLodMemory.this) {
                if (closed || actualBytes < 0) throw new IllegalStateException("Invalid LOD allocation ticket");
                long extra = actualBytes-bytes;
                if (extra > 0) check(extra, limit, heap, devicePreferred);
                live += extra;
                bytes=actualBytes;
                peak=Math.max(peak,live);
            }
        }
        /** Release only after native destruction, never merely when Java ownership changes. */
        @Override public void close() {
            synchronized (RtLodMemory.this) {
                if (closed) return;
                live-=bytes;
                closed=true;
            }
        }
    }
}
