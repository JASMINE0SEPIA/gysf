package dev.comfyfluffy.caustica.rt.terrain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class LodBuildWindowTest {
    @Test void countAndByteLimitsBoundTheWholePipeline() {
        var window=new LodBuildWindow(3,100);var a=window.acquire(40);var b=window.acquire(40);
        assertNull(window.acquire(30));var c=window.acquire(20);assertNotNull(c);
        assertNull(window.acquire(0));assertEquals(3,window.peak());
        window.release(b);assertNotNull(window.acquire(40));
        assertEquals(100,window.bytes());assertNotNull(a);
    }
    @Test void oversizedSourceRunsAloneInsteadOfPermanentlyStalling() {
        var window=new LodBuildWindow(8,100);var large=window.acquire(200);
        assertNotNull(large);assertNull(window.acquire(1));window.release(large);
        assertNotNull(window.acquire(100));
    }
    @Test void delayedOrDuplicateEpochCompletionCannotReleaseAnotherBatch() {
        var window=new LodBuildWindow(1,100);var old=window.acquire(80);window.release(old);
        var next=window.acquire(90);window.release(old);
        assertEquals(1,window.count());assertEquals(90,window.bytes());
        window.release(next);assertEquals(0,window.count());assertEquals(0,window.bytes());
    }
    @Test void outOfOrderCompletionsRestoreAdmissionWithoutLosingAccounting() {
        var window=new LodBuildWindow(3,100);var a=window.acquire(20);var b=window.acquire(30);var c=window.acquire(40);
        window.release(c);window.release(a);assertEquals(30,window.bytes());
        window.release(b);assertEquals(0,window.count());assertEquals(0,window.bytes());
    }
}
