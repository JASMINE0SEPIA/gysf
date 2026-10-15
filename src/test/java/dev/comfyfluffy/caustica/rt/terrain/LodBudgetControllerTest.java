package dev.comfyfluffy.caustica.rt.terrain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class LodBudgetControllerTest {
    private static final long SECOND=1_000_000_000L;
    @Test void repeatedPressureHalvesDistanceButNeverRequestsAnEmptyHorizon() {
        var c=new LodBudgetController();
        c.pressure(1024,0); assertEquals(512,c.limitBlocks());
        c.pressure(1024,SECOND); assertEquals(256,c.limitBlocks());
        c.pressure(1024,2*SECOND); assertEquals(128,c.limitBlocks());
        c.pressure(1024,3*SECOND); assertEquals(128,c.limitBlocks()); assertEquals(3,c.reductionCount());
    }
    @Test void recoveryRequiresQuietTimeAndComfortableHeadroomThenGrowsGradually() {
        var c=new LodBudgetController(); c.pressure(512,0); c.pressure(512,SECOND);
        assertFalse(c.recover(512,8*SECOND,true));
        assertFalse(c.recover(512,9*SECOND,false));
        assertTrue(c.recover(512,9*SECOND,true)); assertEquals(256,c.limitBlocks());
        assertFalse(c.recover(512,10*SECOND,true));
        assertTrue(c.recover(512,12*SECOND,true)); assertEquals(384,c.limitBlocks());
        assertTrue(c.recover(512,15*SECOND,true)); assertEquals(0,c.limitBlocks()); assertEquals(3,c.recoveryCount());
    }
    @Test void freshPressureRestartsTheRecoveryDelay() {
        var c=new LodBudgetController(); c.pressure(512,0); c.pressure(512,7*SECOND);
        assertFalse(c.recover(512,8*SECOND,true)); assertTrue(c.recover(512,15*SECOND,true));
    }
    @Test void recoveryRespectsALoweredUserRequestAndResetClearsOnlyTheTemporaryLimit() {
        var c=new LodBudgetController(); c.pressure(1024,0);
        assertTrue(c.recover(256,8*SECOND,true)); assertEquals(0,c.limitBlocks());
        c.pressure(512,9*SECOND); c.reset(); assertEquals(0,c.limitBlocks()); assertEquals(2,c.reductionCount());
    }
}
