package dev.comfyfluffy.caustica.rt.terrain;

import dev.comfyfluffy.caustica.rt.RtLodMemory;
import org.junit.jupiter.api.Test;
import static dev.comfyfluffy.caustica.rt.RtLodMemory.MIB;
import static org.junit.jupiter.api.Assertions.*;

final class NearBudgetControllerTest {
    @Test void temporaryHeadroomWhileBuildersAreBusyCannotRestartRecovery() {
        var policy=new NearBudgetController();
        assertEquals(24,policy.update(32,true,0,heap(7497,true),false));
        var healthy=heap(3000,true);
        // The small checkpoint looks comfortable, but the rest of its replacement still needs memory.
        assertEquals(24,policy.update(32,true,SECOND,healthy,false));
        assertEquals(24,policy.update(32,true,60*SECOND,healthy,false));
        assertEquals(24,policy.update(32,true,61*SECOND,healthy,true));
        assertEquals(24,policy.update(32,true,70*SECOND,healthy,true));
        assertEquals(26,policy.update(32,true,71*SECOND,healthy,true));
        // Growing the near ring dispatches another build; it must settle before the next increase.
        assertEquals(26,policy.update(32,true,72*SECOND,healthy,false));
        assertEquals(26,policy.update(32,true,90*SECOND,healthy,true));
        assertEquals(28,policy.update(32,true,100*SECOND,healthy,true));
        // Busy builders never suppress an actual pressure response.
        assertEquals(21,policy.update(32,true,102*SECOND,heap(7497,true),false));
    }

    @Test void retiredGeometryInReservedBlocksStopsFalseContractionAndPermitsRecovery() {
        var policy=new NearBudgetController();
        var tight=new RtLodMemory.Heap(6450*MIB,6920*MIB,4800*MIB,0,true,100*MIB);
        assertEquals(24,policy.update(32,true,0,tight));
        var retired=new RtLodMemory.Heap(6450*MIB,6920*MIB,515*MIB,0,true,4800*MIB);
        assertEquals(24,policy.update(32,true,2*SECOND,retired));
        assertTrue(policy.canDispatch(true,retired));
        assertEquals(24,policy.update(32,true,11*SECOND,retired));
        assertEquals(26,policy.update(32,true,12*SECOND,retired));
        // If the unused blocks are consumed, their reuse credit disappears; it is not permanent VRAM.
        assertEquals(19,policy.update(32,true,14*SECOND,tight));
    }

    private static final long SECOND=1_000_000_000L;
    private static RtLodMemory.Heap heap(long usage,boolean driver) {
        return new RtLodMemory.Heap(usage*MIB,6999*MIB,0,0,driver);
    }

    @Test void actualOverBudgetTraceContractsTheWorkingSetWithoutChangingTheRequest() {
        var policy=new NearBudgetController();var over=heap(7497,true);
        assertEquals(24,policy.update(32,true,0,over));
        assertEquals(24,policy.update(32,true,SECOND,over));
        int radius=24;
        for(int s=2;s<=30;s+=2)radius=policy.update(32,true,s*SECOND,over);
        assertEquals(4,radius);
        assertEquals(2,policy.update(2,true,32*SECOND,over));
        assertFalse(policy.canDispatch(true,over));
    }

    @Test void staleOrUnavailableLodAndDriverInputsCannotSilentlyReduceExactTerrain() {
        var policy=new NearBudgetController();
        assertEquals(32,policy.update(32,false,0,heap(7497,true)));
        assertEquals(32,policy.update(32,true,0,heap(7497,false)));
        assertTrue(policy.canDispatch(false,heap(7497,true)));
        assertTrue(policy.canDispatch(true,heap(7497,false)));
    }

    @Test void recoveryRequiresSustainedHeadroomAndEventuallyReturnsToUserDistance() {
        var policy=new NearBudgetController();
        assertEquals(24,policy.update(32,true,0,heap(7497,true)));
        var healthy=heap(3000,true);
        assertEquals(24,policy.update(32,true,2*SECOND,healthy));
        assertEquals(24,policy.update(32,true,11*SECOND,healthy));
        assertEquals(26,policy.update(32,true,12*SECOND,healthy));
        assertEquals(26,policy.update(32,true,13*SECOND,healthy));
        assertEquals(28,policy.update(32,true,14*SECOND,healthy));
        assertEquals(30,policy.update(32,true,16*SECOND,healthy));
        assertEquals(32,policy.update(32,true,18*SECOND,healthy));
        assertTrue(policy.canDispatch(true,healthy));
    }

    @Test void intermediateHeadroomBreaksTheRecoveryWindowAndResetClearsWorldHistory() {
        var policy=new NearBudgetController();
        policy.update(32,true,0,heap(7497,true));
        policy.update(32,true,SECOND,heap(3000,true));
        assertEquals(24,policy.update(32,true,10*SECOND,heap(6000,true)));
        assertEquals(24,policy.update(32,true,12*SECOND,heap(3000,true)));
        assertEquals(24,policy.update(32,true,21*SECOND,heap(3000,true)));
        assertEquals(26,policy.update(32,true,22*SECOND,heap(3000,true)));
        policy.reset();
        assertEquals(32,policy.update(32,true,23*SECOND,heap(3000,true)));
    }
}
