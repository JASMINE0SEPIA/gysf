package dev.comfyfluffy.caustica.rt.terrain;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class VoxyDetailCacheTest {
    @Test void onlyMatchingContentCanBypassRebuildingAndStaleFallbackRemainsOwned() {
        var cache=new VoxyDetailCache<String>();var source=entry(1,5,100);cache.put(source,200);
        assertNull(cache.takeMatching(1,6));assertEquals(100,cache.bytes());
        assertSame(source,cache.takeMatching(1,5));assertEquals(0,cache.bytes());
        assertNull(cache.takeMatching(1,5));
    }
    private static VoxyDetailCache.Entry<String> entry(long key,long revision,long bytes) {
        return new VoxyDetailCache.Entry<>(new VoxyLodTransition.Volume(key,(int)key*32,0,0,32),
                revision,List.of("batch1-"+key,"batch2-"+key),bytes);
    }
    @Test void takingWholeSourceTransfersOwnershipAndUnchargesAllItsBytes() {
        var cache=new VoxyDetailCache<String>();var source=entry(1,1,100);
        assertTrue(cache.put(source,200).isEmpty());assertEquals(100,cache.bytes());
        assertSame(source,cache.take(1));assertNull(cache.take(1));assertEquals(0,cache.bytes());
        assertEquals(2,source.batches().size());
    }
    @Test void budgetEvictsOldestWholeSourcesWithoutSplittingTheirBatches() {
        var cache=new VoxyDetailCache<String>();var a=entry(1,1,100);var b=entry(2,1,100);
        cache.put(a,200);cache.put(b,200);
        assertEquals(List.of(a),cache.put(entry(3,1,100),200));assertEquals(200,cache.bytes());
        assertNull(cache.take(1));assertSame(b,cache.take(2));
    }
    @Test void delayedOlderRetirementCannotReplaceANewerCachedRevision() {
        var cache=new VoxyDetailCache<String>();var fresh=entry(1,2,100);var stale=entry(1,1,200);
        cache.put(fresh,300);assertEquals(List.of(stale),cache.put(stale,300));
        assertSame(fresh,cache.take(1));assertEquals(0,cache.bytes());
    }
    @Test void replacementReturnsPreviousOwnerExactlyOnce() {
        var cache=new VoxyDetailCache<String>();var old=entry(1,1,100);var fresh=entry(1,2,150);
        cache.put(old,300);assertEquals(List.of(old),cache.put(fresh,300));
        assertEquals(150,cache.bytes());assertEquals(List.of(fresh),cache.clear());assertTrue(cache.clear().isEmpty());
    }
    @Test void pressureOrZeroBudgetReturnsEveryOptionalOwnerForDestruction() {
        var cache=new VoxyDetailCache<String>();var a=entry(1,1,100);var b=entry(2,1,100);
        cache.put(a,300);assertEquals(List.of(a,b),cache.put(b,0));
        assertEquals(0,cache.bytes());assertEquals(0,cache.size());
    }
    @Test void sourceLargerThanTheEntireBudgetNeverRemainsResident() {
        var cache=new VoxyDetailCache<String>();var large=entry(1,1,301);
        assertEquals(List.of(large),cache.put(large,300));assertEquals(0,cache.bytes());
    }
    @Test void budgetShrinkStillEvictsExistingOwnerWhenAnOlderCallbackArrives() {
        var cache=new VoxyDetailCache<String>();var fresh=entry(1,2,100);var stale=entry(1,1,100);
        cache.put(fresh,200);assertEquals(List.of(stale,fresh),cache.put(stale,0));assertEquals(0,cache.bytes());
    }
}
