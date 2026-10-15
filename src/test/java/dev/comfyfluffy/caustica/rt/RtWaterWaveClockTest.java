package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class RtWaterWaveClockTest {
    @Test void currentAndPreviousTimesSurviveBaseAndHourlyBoundaries() {
        for(double boundary:new double[]{0,8,3600,86400,604808}) {
            var clock=new RtWaterWaveClock();
            double before=boundary-0.01,after=boundary+0.01;
            clock.at(before);
            var frame=clock.at(after);
            assertEquals(after,frame.baseSeconds()+frame.currentTime(),1e-7);
            assertEquals(before,frame.baseSeconds()+frame.previousTime(),1e-7);
            assertTrue(frame.previousTime()<0,"history must cross the base, not restart at zero");
        }
    }

    @Test void firstFramePauseClockRewindAndResetHaveNeutralHistory() {
        var clock=new RtWaterWaveClock();
        var first=clock.at(7.875);
        assertEquals(first.currentTime(),first.previousTime());
        var adjacent=clock.at(8.125); // exact 0.25 s history limit
        assertEquals(7.875,adjacent.baseSeconds()+adjacent.previousTime(),1e-7);
        var pause=clock.at(9);
        assertEquals(pause.currentTime(),pause.previousTime());
        var rewind=clock.at(8);
        assertEquals(rewind.currentTime(),rewind.previousTime());
        clock.resetHistory();
        var reset=clock.at(8.01);
        assertEquals(reset.currentTime(),reset.previousTime());
    }

    @Test void LongRunningClockRetainsSubFramePrecision() {
        var clock=new RtWaterWaveClock();
        double before=604807.9999,after=604808.004;
        clock.at(before);
        var frame=clock.at(after);
        assertEquals(after-before,frame.currentTime()-frame.previousTime(),1e-7);
        assertEquals(before,frame.baseSeconds()+frame.previousTime(),1e-7);
        assertTrue(frame.currentTime()<0.01);
    }
}
