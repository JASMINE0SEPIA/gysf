package dev.comfyfluffy.caustica.rt.terrain;

import java.util.HashMap;
import java.util.Map;

/** Render-thread admission covers packing through GPU compaction, including stale-epoch completions. */
final class LodBuildWindow {
    record Ticket(long id, long bytes) {}
    private final int maxCount;
    private final long maxBytes;
    private final Map<Long, Ticket> active = new HashMap<>();
    private long sequence, bytes;
    private int peak;

    LodBuildWindow(int maxCount, long maxBytes) {
        if (maxCount < 1 || maxBytes < 1) throw new IllegalArgumentException("Invalid LOD build window");
        this.maxCount = maxCount; this.maxBytes = maxBytes;
    }
    Ticket acquire(long estimatedBytes) {
        if (estimatedBytes < 0) throw new IllegalArgumentException("Negative LOD batch size");
        // One oversized source still makes progress; its allocation remains subject to the native budget.
        if (active.size() >= maxCount || !active.isEmpty() && estimatedBytes > maxBytes - bytes) return null;
        Ticket ticket = new Ticket(++sequence, estimatedBytes);
        active.put(ticket.id, ticket); bytes = Math.addExact(bytes, estimatedBytes);
        peak = Math.max(peak,active.size());
        return ticket;
    }
    void release(Ticket ticket) {
        if (ticket != null && active.remove(ticket.id,ticket)) bytes -= ticket.bytes;
    }
    int count() { return active.size(); }
    int peak() { return peak; }
    long bytes() { return bytes; }
}
