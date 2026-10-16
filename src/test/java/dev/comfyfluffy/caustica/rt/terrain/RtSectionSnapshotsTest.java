package dev.comfyfluffy.caustica.rt.terrain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class RtSectionSnapshotsTest {
    @Test void reloadedNeighborCannotReuseItsOldEmptySnapshotAtTheSameCoordinates() {
        var cache=new RtSectionSnapshots();
        Object firstSection=new Object(),reloadedSection=new Object();
        Object empty=new Object(),terrain=new Object();
        assertSame(empty,cache.snapshot(123,firstSection,()->empty));
        assertSame(terrain,cache.snapshot(123,reloadedSection,()->terrain));
        assertSame(terrain,cache.snapshot(123,reloadedSection,()->{throw new AssertionError("copy should be reused");}));
    }
    @Test void dirtyAndWorldClearStillInvalidateTheSameLoadedSection() {
        var cache=new RtSectionSnapshots();Object source=new Object(),old=new Object(),edited=new Object(),nextWorld=new Object();
        assertSame(old,cache.snapshot(123,source,()->old));
        cache.invalidate(123);
        assertSame(edited,cache.snapshot(123,source,()->edited));
        cache.clear();
        assertSame(nextWorld,cache.snapshot(123,source,()->nextWorld));
    }
}
