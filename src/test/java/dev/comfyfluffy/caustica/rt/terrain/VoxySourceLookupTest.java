package dev.comfyfluffy.caustica.rt.terrain;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class VoxySourceLookupTest {
    private static VoxyLodTransition.Volume cube(long key,int x,int y,int z,int width) {
        return new VoxyLodTransition.Volume(key,x,y,z,width);
    }
    @Test void queryIncludesNestedFallbackAndEmptyOwnersButNotAncestorsOrAdjacentCubes() {
        var index=new VoxySourceLookup();
        for(var v:List.of(cube(1,-128,-128,-128,128),cube(2,-128,-128,-128,64),
                cube(3,-96,-96,-96,32),cube(4,-64,-128,-128,64),cube(5,-128,0,-128,64)))index.put(v);
        assertEquals(Set.of(2L,3L),index.contained(cube(99,-128,-128,-128,64)));
        assertEquals(Set.of(3L),index.contained(cube(99,-96,-96,-96,32)));
    }
    @Test void movingOrRemovingAKeyCannotLeaveItsOldBoundsBehind() {
        var index=new VoxySourceLookup();
        index.put(cube(1,0,0,0,32));index.put(cube(1,32,0,0,32));
        assertEquals(Set.of(),index.contained(cube(99,0,0,0,32)));
        assertEquals(Set.of(1L),index.contained(cube(99,32,0,0,32)));
        index.remove(1);index.remove(1);
        assertEquals(Set.of(),index.contained(cube(99,0,0,0,64)));
    }
    @Test void duplicateBoundsAreRejectedWithoutLosingEitherOriginalKey() {
        var index=new VoxySourceLookup();index.put(cube(1,0,0,0,32));index.put(cube(2,32,0,0,32));
        assertThrows(IllegalArgumentException.class,()->index.put(cube(2,0,0,0,32)));
        assertEquals(Set.of(1L,2L),index.contained(cube(99,0,0,0,64)));
    }
    @Test void indexedQueriesMatchBruteForceAcrossNestedSignedCoordinatesAndRemovals() {
        Random rng=new Random(81769);var index=new VoxySourceLookup();
        Map<Long,VoxyLodTransition.Volume> reference=new HashMap<>();Set<String> bounds=new HashSet<>();
        for(long key=1;key<=5000;) {
            int width=32<<rng.nextInt(5),x=(rng.nextInt(32)-16)*width,
                    y=(rng.nextInt(16)-8)*width,z=(rng.nextInt(32)-16)*width;
            if(!bounds.add(x+":"+y+":"+z+":"+width))continue;
            var v=cube(key++,x,y,z,width);reference.put(v.key(),v);index.put(v);
        }
        for(int i=0;i<300;i++) {
            int width=32<<rng.nextInt(5);
            var ready=cube(-1,(rng.nextInt(32)-16)*width,(rng.nextInt(16)-8)*width,(rng.nextInt(32)-16)*width,width);
            Set<Long> expected=new HashSet<>();
            reference.forEach((key,v)->{if(VoxySourceOwnership.contains(ready,v))expected.add(key);});
            assertEquals(expected,index.contained(ready));
            for(long key:expected){index.remove(key);reference.remove(key);}
        }
    }
    @Test void coarserOwnershipLookupMatchesContainmentIncludingNegativeHeights() {
        var fine=cube(1,-32,-64,-32,32);var parent=cube(2,-64,-64,-64,64);
        var other=cube(3,0,-64,0,64);
        var index=new VoxySourceOwnership(List.of(fine,parent,other));
        assertTrue(index.hasCoarser(fine));assertFalse(index.hasCoarser(parent));
        assertFalse(new VoxySourceOwnership(List.of(fine,other)).hasCoarser(fine));
    }
}
