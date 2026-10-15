package dev.comfyfluffy.caustica.rt.terrain;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class VoxyLodTransitionTest {
    private static VoxyLodTransition.Volume cube(long key, int x, int y, int z, int w) {
        return new VoxyLodTransition.Volume(key, x, y, z, w);
    }
    private static List<VoxyLodTransition.Volume> children(int x, int y, int z) {
        List<VoxyLodTransition.Volume> result = new ArrayList<>();
        for (int i = 0; i < 8; i++)
            result.add(cube(i + 1, x + (i & 1) * 32, y + ((i >> 2) & 1) * 32,
                    z + ((i >> 1) & 1) * 32, 32));
        return result;
    }

    @Test void refinementWaitsForAllEightChildrenIncludingTheOtherHeight() {
        var next = children(0, -64, 0);
        var t = new VoxyLodTransition(List.of(cube(100, 0, -64, 0, 64)), next);
        for (int i = 1; i <= 7; i++) assertNull(t.completeBatch(i));
        var swap = t.completeBatch(8);
        assertEquals(Set.of(100L), swap.retired());
        assertEquals(Set.of(1L,2L,3L,4L,5L,6L,7L,8L), swap.activated());
    }

    @Test void verticalNeighborsPublishIndependentlyDespiteIdenticalXZFootprints() {
        var t = new VoxyLodTransition(List.of(cube(100,0,0,0,64),cube(200,0,64,0,64)),
                List.of(cube(1,0,0,0,32),cube(2,0,64,0,32)));
        assertEquals(Set.of(100L), t.completeBatch(1).retired());
        assertEquals(Set.of(200L), t.completeBatch(2).retired());
    }

    @Test void coarseningRetiresEveryFineSourceInTheSameSwap() {
        var t = new VoxyLodTransition(children(-64,-64,-64),List.of(cube(100,-64,-64,-64,64)));
        assertEquals(Set.of(1L,2L,3L,4L,5L,6L,7L,8L),t.completeBatch(100).retired());
    }

    @Test void emptyTargetIsAnExplicitReplacementRatherThanMissingCoverage() {
        var t = new VoxyLodTransition(List.of(cube(1,0,0,0,32)),List.of(cube(1,0,0,0,32)));
        // An empty target completes without allocating a BLAS; the consumer retires its old geometry.
        assertEquals(Set.of(1L),t.completeBatch(1).retired());
        assertNull(t.completeBatch(1));
    }

    @Test void sourceOutsideNewSelectionCanRetireWithoutWaitingForUnrelatedBuilds() {
        var t = new VoxyLodTransition(List.of(cube(1,0,0,0,32)),List.of(cube(2,32,0,0,32)));
        assertEquals(Set.of(1L),t.obsoleteSources());
        assertTrue(t.completeBatch(2).retired().isEmpty());
    }

    @Test void initialSourcesAndSameKeyRebuildsCompleteIndependently() {
        var t = new VoxyLodTransition(List.of(cube(1,0,0,0,32)),
                List.of(cube(1,0,0,0,32),cube(2,32,0,0,32)));
        assertTrue(t.completeBatch(2).retired().isEmpty());
        assertEquals(Set.of(1L),t.completeBatch(1).retired());
    }

    @Test void multipleBlasBatchesNeverPublishAChangedSourcePartially() {
        var t = new VoxyLodTransition(List.of(cube(1,0,0,0,32)),List.of(cube(1,0,0,0,32)),Map.of(1L,3));
        assertNull(t.completeBatch(1));
        assertNull(t.completeBatch(1));
        assertEquals(Set.of(1L),t.completeBatch(1).retired());
        assertNull(t.completeBatch(1));
    }

    @Test void reusedChildAndMultibatchChildMustBothCompleteBeforeRetiringTheirParent() {
        var t = new VoxyLodTransition(List.of(cube(100,0,0,0,64)),
                List.of(cube(1,0,0,0,32),cube(2,32,0,0,32)),Map.of(1L,1,2L,3));
        assertNull(t.completeBatch(2));
        assertNull(t.completeBatch(1));
        assertNull(t.completeBatch(2));
        assertEquals(Set.of(100L),t.completeBatch(2).retired());
    }

    @Test void arbitraryCompletionOrdersNeverPublishOverlappingOrHalfReplacedBranches() {
        List<VoxyLodTransition.Volume> old = List.of(cube(100,-64,-64,-64,64),cube(200,0,0,0,64));
        List<VoxyLodTransition.Volume> next = new ArrayList<>(children(-64,-64,-64));
        next.add(cube(300,0,0,0,64));
        for (int seed=0; seed<100; seed++) {
            var t = new VoxyLodTransition(old,next);
            Map<Long,VoxyLodTransition.Volume> visible = new HashMap<>(), desired = new HashMap<>();
            old.forEach(v->visible.put(v.key(),v)); next.forEach(v->desired.put(v.key(),v));
            List<Long> order = new ArrayList<>(desired.keySet()); Collections.shuffle(order,new Random(seed));
            for (long key:order) {
                var swap=t.completeBatch(key);
                if(swap==null) continue;
                swap.retired().forEach(visible::remove);
                swap.activated().forEach(k->visible.put(k,desired.get(k)));
                long volume=0;
                for(var v:visible.values()) volume+=(long)v.width()*v.width()*v.width();
                assertEquals(2L*64*64*64,volume);
                // Constructor validates that the visible set is a non-overlapping octree partition.
                assertDoesNotThrow(()->new VoxyLodTransition(List.of(),List.copyOf(visible.values())));
            }
            assertEquals(desired,visible);
        }
    }

    @Test void malformedOrOverlappingProviderSelectionsFailInsteadOfSilentlyDroppingFaces() {
        assertThrows(IllegalArgumentException.class,()->cube(1,1,0,0,32));
        assertThrows(IllegalArgumentException.class,()->new VoxyLodTransition(List.of(),
                List.of(cube(1,0,0,0,64),cube(2,0,0,0,32))));
        assertThrows(IllegalArgumentException.class,()->new VoxyLodTransition(List.of(),
                List.of(cube(1,0,0,0,32),cube(1,32,0,0,32))));
    }
}
