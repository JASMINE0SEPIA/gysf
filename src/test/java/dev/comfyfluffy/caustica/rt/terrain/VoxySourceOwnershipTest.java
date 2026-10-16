package dev.comfyfluffy.caustica.rt.terrain;

import org.junit.jupiter.api.Test;
import java.nio.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class VoxySourceOwnershipTest {
    private static VoxyLodTransition.Volume cube(long k, int x, int y, int z, int w) {
        return new VoxyLodTransition.Volume(k,x,y,z,w);
    }

    @Test void firstFineSourceImmediatelyClipsCoarseWithoutWaitingForDistantSiblings() {
        Map<Long,VoxyLodTransition.Volume> active = new HashMap<>();
        active.put(100L,cube(100,0,-64,0,64));
        var fine=cube(1,0,-64,0,32);
        assertTrue(VoxySourceOwnership.activate(active,fine).isEmpty());
        var index=new VoxySourceOwnership(active.values());
        assertTrue(index.hasFiner(3,-63,5,64));
        assertFalse(index.hasFiner(35,-63,5,64));
        assertFalse(index.hasFiner(3,-31,5,64));
        assertFalse(index.hasFiner(3,-63,5,32));
        assertEquals(2,active.size()); // unready neighbor still has its fallback
    }

    @Test void coarseningRetiresDescendantsOnlyInsideReadyReplacement() {
        Map<Long,VoxyLodTransition.Volume> active=new HashMap<>();
        active.put(1L,cube(1,-64,-64,-64,32));
        active.put(2L,cube(2,-32,-32,-32,32));
        active.put(3L,cube(3,0,-64,-64,32));
        assertEquals(Set.of(1L,2L),VoxySourceOwnership.activate(active,cube(4,-64,-64,-64,64)));
        assertEquals(Set.of(3L,4L),active.keySet());
    }

    @Test void emptyFineMarkerAndCancelledMixedSelectionSurviveReplanning() {
        var coarse=cube(100,-64,-64,-64,64);
        var fine=cube(1,-64,-64,-64,32); // empty marker has no geometry but owns its complete cube
        var next=List.of(fine,cube(2,-32,-64,-64,32));
        var transition=new VoxyLodTransition(List.of(coarse,fine),next,Map.of(1L,2),true);
        assertNull(transition.completeBatch(1));
        assertNull(transition.completeBatch(1));
        var swap=transition.completeBatch(2);
        assertEquals(Set.of(100L,1L),swap.retired());
        assertEquals(Set.of(1L,2L),swap.activated());
        assertTrue(new VoxySourceOwnership(List.of(coarse,fine)).hasFiner(-64,-64,-64,64));
    }

    @Test void serializedShaderLookupMatchesReferenceAcrossCollisionsSignedCoordinatesAndRebases() {
        Random random=new Random(78346);
        List<VoxyLodTransition.Volume> volumes=new ArrayList<>();
        Set<String> bounds=new HashSet<>();
        for(int i=0; i<5000;) {
            int w=32<<random.nextInt(5),x=(random.nextInt(2000)-1000)*w,
                    y=(random.nextInt(32)-16)*w,z=(random.nextInt(2000)-1000)*w;
            if(bounds.add(x+","+y+","+z+","+w))volumes.add(cube(++i,x,y,z,w));
        }
        var index=new VoxySourceOwnership(volumes);
        ByteBuffer bytes=ByteBuffer.allocate(index.byteSize()).order(ByteOrder.LITTLE_ENDIAN);
        index.write(bytes);
        for(var v:volumes) for(int side:new int[]{0,v.width()-1}) {
            int x=v.x()+side,y=v.y()+side,z=v.z()+side;
            for(int width=32;width<=512;width*=2)
                assertEquals(index.hasFiner(x,y,z,width),shaderLookup(bytes,x,y,z,width));
        }
        assertFalse(index.hasFiner(29_999_999,300,-29_999_999,512));
    }

    private static boolean shaderLookup(ByteBuffer b,int x,int y,int z,int sourceWidth) {
        assertEquals(VoxySourceOwnership.MAGIC,b.getInt(4));
        int mask=b.getInt(0);
        for(int w=32;w<sourceWidth;w*=2) {
            int ox=x&~(w-1),oy=y&~(w-1),oz=z&~(w-1);
            int slot=VoxySourceOwnership.hash(ox,oy,oz,w)&mask;
            for(int n=0;n<=mask;n++) {
                int p=16+slot*16;
                if(b.getInt(p+12)==0)break;
                if(b.getInt(p)==ox&&b.getInt(p+4)==oy&&b.getInt(p+8)==oz&&b.getInt(p+12)==w)return true;
                slot=(slot+1)&mask;
            }
        }
        return false;
    }

    @Test void duplicateBoundsCannotSilentlyOverwriteAnotherSource() {
        assertThrows(IllegalArgumentException.class,()->new VoxySourceOwnership(List.of(
                cube(1,0,0,0,32),cube(2,0,0,0,32))));
    }

    @Test void immutableMapIterationOrderDoesNotChangeSerializedOwnership() {
        List<VoxyLodTransition.Volume> list=new ArrayList<>();
        for(int i=0;i<200;i++)list.add(cube(i,i*32,-32,0,32));
        var first=new VoxySourceOwnership(list);
        ByteBuffer a=ByteBuffer.allocate(first.byteSize());first.write(a);a.flip();
        Collections.shuffle(list,new Random(764));
        var second=new VoxySourceOwnership(list);
        ByteBuffer b=ByteBuffer.allocate(second.byteSize());second.write(b);b.flip();
        assertEquals(a,b);
    }

    @Test void inwardFaceProbeKeepsOwnershipAcrossRebasesNearBothWorldBorders() {
        var fine=cube(1,29_999_968,224,-30_000_000,32);
        var index=new VoxySourceOwnership(List.of(fine));
        int originX=29_999_872,originZ=-29_999_872;
        float localX=fine.x()-originX+32f,localZ=fine.z()-originZ;
        // The +X face belongs to the voxel below that face, regardless of the ray's direction.
        assertTrue(index.hasFiner((int)Math.floor(localX-.01f)+originX,230,fine.z()+2,64));
        assertFalse(index.hasFiner((int)Math.floor(localX+.01f)+originX,230,fine.z()+2,64));
        // The -Z face likewise owns the inside cell, with signed floor after rebasing.
        assertTrue(index.hasFiner(fine.x()+2,230,(int)Math.floor(localZ+.01f)+originZ,64));
        assertFalse(index.hasFiner(fine.x()+2,230,(int)Math.floor(localZ-.01f)+originZ,64));
    }

    @Test void visibleHillsidePrecedesUndergroundSourcesInTheSameHorizontalColumn() {
        var hill=cube(1,-224,160,128,32);
        var underground=cube(2,-288,-32,160,32);
        assertTrue(VoxySourceOwnership.distanceTo(hill,-257,240,174)
                <VoxySourceOwnership.distanceTo(underground,-257,240,174));
        assertEquals(0,VoxySourceOwnership.distanceTo(hill,-220,170,150));
        assertEquals(48,VoxySourceOwnership.distanceTo(hill,-257,240,174));
    }

    @Test void arbitraryFineCompletionOrderNeverLosesUnbuiltNeighborsOrWaitsForTheirDetail() {
        var parent=cube(1000,-128,-128,-128,128);
        List<VoxyLodTransition.Volume> leaves=new ArrayList<>();
        for(int y=0;y<4;y++)for(int z=0;z<4;z++)for(int x=0;x<4;x++)
            leaves.add(cube(leaves.size()+1,-128+x*32,-128+y*32,-128+z*32,32));
        for(int seed=0;seed<20;seed++) {
            Map<Long,VoxyLodTransition.Volume> active=new HashMap<>(Map.of(1000L,parent));
            Set<Long> completed=new HashSet<>();
            List<VoxyLodTransition.Volume> order=new ArrayList<>(leaves);Collections.shuffle(order,new Random(seed));
            var transition=new VoxyLodTransition(List.of(parent),leaves);
            for(var ready:order) {
                VoxySourceOwnership.activate(active,ready);completed.add(ready.key());
                var swap=transition.completeBatch(ready.key());
                if(completed.size()<64)assertNull(swap);
                else {assertNotNull(swap);swap.retired().forEach(active::remove);}
                var index=new VoxySourceOwnership(active.values());
                for(var leaf:leaves) {
                    assertEquals(completed.contains(leaf.key()),index.hasFiner(leaf.x()+16,leaf.y()+16,leaf.z()+16,128));
                    assertTrue(active.containsKey(leaf.key())||active.containsKey(parent.key()));
                }
            }
            assertEquals(64,active.size());
            // Cancellation/re-plan from any nested intermediate set is accepted; duplicates still fail.
            new VoxyLodTransition(List.copyOf(active.values()),List.of(parent),Map.of(),true);
        }
    }
}
