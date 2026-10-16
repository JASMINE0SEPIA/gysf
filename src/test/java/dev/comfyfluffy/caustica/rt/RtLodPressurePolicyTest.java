package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class RtLodPressurePolicyTest {
    @Test void reservingHeadroomForNearStreamingDoesNotAlsoCutAwayItsDistantReplacement() {
        var memory=new RtLodMemory();
        var heap=new RtLodMemory.Heap(1900*RtLodMemory.MIB,2048*RtLodMemory.MIB,0,0,true);
        var pressure=assertThrows(RtLodMemory.Pressure.class,()->memory.reserve(RtLodMemory.MIB,1024*RtLodMemory.MIB,heap,true));
        assertFalse(pressure.mayReduceDistance());
        assertEquals(0,memory.liveBytes());
    }
    @Test void actualLodBudgetAndNativeFailuresRetainTheirBoundedDegradation() {
        var memory=new RtLodMemory();
        var heap=new RtLodMemory.Heap(0,8192*RtLodMemory.MIB,0,0,true);
        var pressure=assertThrows(RtLodMemory.Pressure.class,()->memory.reserve(2*RtLodMemory.MIB,RtLodMemory.MIB,heap,true));
        assertTrue(pressure.mayReduceDistance());
        assertTrue(memory.nativeFailure(RtLodMemory.MIB,1024*RtLodMemory.MIB).mayReduceDistance());
    }
}
