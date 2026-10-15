package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static dev.comfyfluffy.caustica.rt.RtLodMemory.MIB;
import static org.junit.jupiter.api.Assertions.*;

final class RtLodMemoryTest {
    @Test void reusableAllocatorBlocksAreNotCountedAsFreshDriverGrowth() {
        var heap=new RtLodMemory.Heap(6450*MIB,6920*MIB,515*MIB,0,true,4800*MIB);
        assertEquals(470*MIB,heap.availableBytes());
        assertEquals(2870*MIB,heap.allocatorHeadroomBytes());
        var memory=new RtLodMemory();
        try(var ticket=memory.reserve(512*MIB,1024*MIB,heap,true)) {assertEquals(512*MIB,memory.liveBytes());}
        assertEquals(0,memory.deferralCount());
        var noPool=new RtLodMemory.Heap(6450*MIB,6920*MIB,515*MIB,0,true);
        assertThrows(RtLodMemory.Pressure.class,()->memory.reserve(1,1024*MIB,noPool,true));
    }

    @Test void reuseCreditStillLeavesFragmentationSlackAndChargesCachedSampleGrowth() {
        var heap=new RtLodMemory.Heap(750*MIB,1024*MIB,100*MIB,0,true,512*MIB);
        var memory=new RtLodMemory();
        try(var first=memory.reserve(200*MIB,1024*MIB,heap,true)) {
            assertThrows(RtLodMemory.Pressure.class,()->memory.reserve(80*MIB,1024*MIB,heap,true));
        }
        var negativePool=new RtLodMemory.Heap(750*MIB,1024*MIB,100*MIB,0,true,-MIB);
        assertEquals(negativePool.availableBytes(),negativePool.allocatorHeadroomBytes());
    }

    private static RtLodMemory.Heap unknown() { return new RtLodMemory.Heap(0,0,0,0,false); }

    @Test void nativePaddingAndRetirementRemainChargedUntilDestruction() {
        var memory=new RtLodMemory();
        var old=memory.reserve(100,1000,unknown(),true); old.commit(128);
        var replacement=memory.reserve(200,1000,unknown(),true); replacement.commit(256);
        assertEquals(384,memory.liveBytes());
        old.close(); old.close();
        assertEquals(256,memory.liveBytes());
        replacement.close();
        assertEquals(0,memory.liveBytes()); assertEquals(384,memory.peakBytes());
    }
    @Test void rejectedAllocationsDoNotConsumeTheQuota() {
        var memory=new RtLodMemory();
        var ticket=memory.reserve(80,100,unknown(),true);
        assertThrows(RtLodMemory.Pressure.class,()->memory.reserve(21,100,unknown(),true));
        assertEquals(80,memory.liveBytes()); assertEquals(1,memory.deferralCount());
        ticket.close(); assertEquals(0,memory.liveBytes());
    }
    @Test void admissionPreviewWaitsForRealHeadroomWithoutRecountingTheSameFailure() {
        var memory=new RtLodMemory();
        var tight=new RtLodMemory.Heap(7497*MIB,6999*MIB,6198*MIB,0,true);
        var rejected=assertThrows(RtLodMemory.Pressure.class,()->memory.reserve(108864,1749*MIB,tight,true));
        assertEquals(108864,rejected.requestedBytes());
        for(int i=0;i<100;i++)assertFalse(memory.canReserve(rejected.requestedBytes(),1749*MIB,tight,true));
        assertEquals(1,memory.deferralCount());assertEquals(0,memory.liveBytes());
        var healthy=new RtLodMemory.Heap(5000*MIB,6999*MIB,4000*MIB,0,true);
        assertTrue(memory.canReserve(rejected.requestedBytes(),1749*MIB,healthy,true));
        try(var ticket=memory.reserve(rejected.requestedBytes(),1749*MIB,healthy,true)) {
            assertEquals(108864,memory.liveBytes());
        }
        assertEquals(0,memory.liveBytes());
    }
    @Test void admissionPreviewAlsoHonorsLiveTicketsAndTheChangedNativeQuota() {
        var memory=new RtLodMemory();var ticket=memory.reserve(80,100,unknown(),true);
        assertFalse(memory.canReserve(21,100,unknown(),true));
        assertFalse(memory.canReserve(1,10,unknown(),true));
        assertTrue(memory.canReserve(20,100,unknown(),true));
        assertEquals(0,memory.deferralCount());
        ticket.close();assertTrue(memory.canReserve(21,100,unknown(),true));
    }
    @Test void alignmentRejectionCanRollBackTheEntireNativeAllocation() {
        var memory=new RtLodMemory(); var ticket=memory.reserve(90,100,unknown(),true);
        assertThrows(RtLodMemory.Pressure.class,()->ticket.commit(128));
        ticket.close(); assertEquals(0,memory.liveBytes());
    }
    @Test void processWideDriverUsageIncludesOtherAllocatorsWithoutAddingTheirUsageAgain() {
        var memory=new RtLodMemory();
        var heap=new RtLodMemory.Heap(900*MIB,1024*MIB,16*MIB,0,true);
        assertThrows(RtLodMemory.Pressure.class,()->memory.reserve(MIB,512*MIB,heap,true));
        assertEquals(0,memory.liveBytes());
    }
    @Test void cachedDriverHeadroomChargesOnlyGrowthSinceItsSnapshot() {
        var memory=new RtLodMemory();
        var heap=new RtLodMemory.Heap(512*MIB,1024*MIB,64*MIB,0,true);
        var first=memory.reserve(200*MIB,1024*MIB,heap,true);
        assertThrows(RtLodMemory.Pressure.class,()->memory.reserve(80*MIB,1024*MIB,heap,true));
        var refreshed=new RtLodMemory.Heap(712*MIB,1024*MIB,264*MIB,200*MIB,true);
        try(var small=memory.reserve(32*MIB,1024*MIB,refreshed,true)) { assertEquals(232*MIB,memory.liveBytes()); }
        first.close();
    }
    @Test void unavailableDriverDataDoesNotPretendToKnowProcessHeadroom() {
        var memory=new RtLodMemory();
        var estimated=new RtLodMemory.Heap(1024*MIB,1024*MIB,1024*MIB,0,false);
        try(var ticket=memory.reserve(MIB,32*MIB,estimated,true)) { assertEquals(MIB,memory.liveBytes()); }
    }
    @Test void reclamationMetadataCanPublishEvenWhenAnExistingWorkingSetExceedsTheNewQuota() {
        var memory=new RtLodMemory(); var old=memory.reserve(80,100,unknown(),true);
        assertThrows(RtLodMemory.Pressure.class,()->memory.reserve(1,10,unknown(),true));
        try(var table=memory.reserve(32,Long.MAX_VALUE,unknown(),false)) { old.close(); assertEquals(32,memory.liveBytes()); }
        assertEquals(0,memory.liveBytes());
    }
    @Test void explicitAndAutomaticLimitsHaveConservativeBounds() {
        assertEquals(MIB,RtLodMemory.limitBytes(1,8L<<30));
        assertEquals(4096*MIB,RtLodMemory.limitBytes(Long.MAX_VALUE,8L<<30));
        assertEquals(256*MIB,RtLodMemory.limitBytes(0,0));
        assertEquals(2048*MIB,RtLodMemory.limitBytes(0,16L<<30));
    }
    @Test void concurrentReservationsCannotOverbookTheWorkingSet() throws Exception {
        var memory=new RtLodMemory(); var accepted=new AtomicInteger();
        var attempted=new CountDownLatch(16); var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(16)) {
            for(int i=0;i<16;i++) pool.submit(()->{
                RtLodMemory.Ticket ticket=null;
                try { ticket=memory.reserve(MIB,8*MIB,unknown(),true); accepted.incrementAndGet(); }
                catch(RtLodMemory.Pressure expected) { }
                finally { attempted.countDown(); }
                try { release.await(); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { if(ticket!=null) ticket.close(); }
            });
            try { assertTrue(attempted.await(5,TimeUnit.SECONDS)); assertEquals(8,accepted.get()); assertEquals(8*MIB,memory.liveBytes()); }
            finally { release.countDown(); }
        }
        assertEquals(0,memory.liveBytes()); assertEquals(8,memory.deferralCount());
    }
}
