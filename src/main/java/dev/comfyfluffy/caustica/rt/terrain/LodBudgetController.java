package dev.comfyfluffy.caustica.rt.terrain;

/** Temporary provider distance degradation with delayed, gradual recovery; no persistent settings. */
final class LodBudgetController {
    private static final long QUIET_NANOS=8_000_000_000L, RECOVERY_NANOS=3_000_000_000L;
    private int limit;
    private long recoverAt, reductions, recoveries;
    int limitBlocks() { return limit; }
    long reductionCount() { return reductions; }
    long recoveryCount() { return recoveries; }
    void reset() { limit=0; recoverAt=0; }

    void pressure(int requested, long now) {
        int before=limit==0 ? requested : Math.min(limit,requested);
        limit=Math.max(128,before/2);
        if (limit<before) reductions++;
        recoverAt=now+QUIET_NANOS;
    }
    boolean recover(int requested, long now, boolean comfortable) {
        if (limit==0 || now<recoverAt || !comfortable) return false;
        int next=Math.min(requested,Math.max(limit+128,limit*3/2));
        limit=next>=requested ? 0 : next;
        recoverAt=now+RECOVERY_NANOS;
        recoveries++;
        return true;
    }
}
