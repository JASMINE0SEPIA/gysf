package dev.comfyfluffy.caustica.rt.terrain;

import dev.comfyfluffy.caustica.rt.RtLodMemory;

/** Transient exact-terrain working set; published LOD owns sections outside the actual ready mask. */
final class NearBudgetController {
    private static final long REDUCTION_DELAY=2_000_000_000L, RECOVERY_DELAY=10_000_000_000L;
    private int radius;
    private long changeAfter,comfortableSince;
    private boolean comfortable;

    void reset() { radius=0;changeAfter=0;comfortableSince=0;comfortable=false; }

    int update(int requested,boolean hasLodSource,long now,RtLodMemory.Heap heap) {
        requested=Math.max(1,requested);
        if(!hasLodSource || !heap.driverReported()) { reset();return requested; }
        if(radius==0)radius=requested;
        radius=Math.min(radius,requested);
        boolean pressure=heap.availableBytes()<heap.reserveBytes();
        if(pressure) {
            comfortable=false;
            if(now>=changeAfter) {
                radius=Math.max(Math.min(requested,4),radius*3/4);
                changeAfter=now+REDUCTION_DELAY;
            }
        } else if(heap.availableBytes()>heap.reserveBytes()*2) {
            if(!comfortable){comfortableSince=now;comfortable=true;}
            if(radius<requested && now>=changeAfter && now-comfortableSince>=RECOVERY_DELAY) {
                radius=Math.min(requested,radius+2);
                changeAfter=now+REDUCTION_DELAY;
            }
        } else comfortable=false;
        return radius;
    }

    boolean canDispatch(boolean hasLodSource,RtLodMemory.Heap heap) {
        return !hasLodSource || !heap.driverReported() || heap.availableBytes()>=heap.reserveBytes();
    }
}
