package dev.comfyfluffy.caustica.rt.terrain;

import dev.comfyfluffy.caustica.rt.RtLodMemory;
import org.junit.jupiter.api.Test;
import static dev.comfyfluffy.caustica.rt.RtLodMemory.MIB;
import static org.junit.jupiter.api.Assertions.*;

final class NearBudgetControllerTest {
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
