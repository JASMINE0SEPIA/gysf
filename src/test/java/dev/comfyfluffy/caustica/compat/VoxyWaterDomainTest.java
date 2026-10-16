package dev.comfyfluffy.caustica.compat;

import org.junit.jupiter.api.Test;
import java.nio.ShortBuffer;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class VoxyWaterDomainTest {
    public record Export(long epoch,long revision,List<Value> tiles,int pending) {}
    public record Value(int x,int y,int z,long revision,ShortBuffer clearance,long[] known) {}
    public static class Bridge {
        static Export next;
        public static int waterDomainVersion() { return 1; }
        public static Export pollWaterDomain(int[] ignored) { return next; }
    }
    public static class Unsupported {
        public static int waterDomainVersion() { return 2; }
    }

    @Test void providerArraysAreCopiedAndTheSameVersionReusesOwnedTiles() throws Exception {
        var reader=new VoxyWaterDomain.Reader(Bridge.class);
        short[] codes={(short)65535}; long[] known={-1,-1,-1,-1};
        Bridge.next=new Export(1,1,List.of(new Value(-1,-64,1,1,ShortBuffer.wrap(codes),known)),0);
        var first=reader.poll(new int[]{-1,-64,1});
        codes[0]=0; known[0]=0;
        assertEquals(3,first.tiles().getFirst().clearance(0,0));
        assertTrue(first.tiles().getFirst().known(0,0));
        assertSame(first.tiles().getFirst(),reader.poll(new int[]{-1,-64,1}).tiles().getFirst());
    }

    @Test void epochAndWorldExitClearThePreviousTileCache() throws Exception {
        var reader=new VoxyWaterDomain.Reader(Bridge.class);
        Bridge.next=scene(1,1,3);
        var before=reader.poll(new int[]{0,64,0}).tiles().getFirst();
        Bridge.next=scene(2,1,0);
        var after=reader.poll(new int[]{0,64,0}).tiles().getFirst();
        assertNotSame(before,after); assertEquals(0,after.clearance(0,0));
        Bridge.next=null; assertNull(reader.poll(new int[]{0,64,0}));
    }

    @Test void malformedUnrequestedAndRegressingSnapshotsAreRejected() throws Exception {
        var reader=new VoxyWaterDomain.Reader(Bridge.class);
        Bridge.next=scene(1,2,3); reader.poll(new int[]{0,64,0});
        Bridge.next=scene(1,1,3);
        assertThrows(IllegalStateException.class,()->reader.poll(new int[]{0,64,0}));
        Bridge.next=new Export(1,3,List.of(new Value(0,64,0,1,ShortBuffer.wrap(new short[]{0}),new long[]{-1,-1,-1,-1})),0);
        assertThrows(IllegalStateException.class,()->reader.poll(new int[]{0,64,0}));
        Bridge.next=scene(1,3,3);
        assertThrows(IllegalStateException.class,()->reader.poll(new int[]{1,64,0}));
        Bridge.next=new Export(1,4,List.of(new Value(0,64,0,4,ShortBuffer.wrap(new short[3]),new long[4])),0);
        assertThrows(IllegalArgumentException.class,()->reader.poll(new int[]{0,64,0}));
        assertThrows(NoSuchMethodException.class,()->new VoxyWaterDomain.Reader(Unsupported.class));
    }

    @Test void unknownSamplesCannotBecomeWetEvenWhenTheirCodeIsNonzero() {
        var tile=new VoxyWaterDomain.Tile(0,0,0,1,new short[]{(short)65535},new long[4]);
        assertFalse(tile.complete()); assertEquals(0,tile.clearance(0,0));
        assertNotEquals(VoxyWaterDomain.Tile.key(-1,0,1),VoxyWaterDomain.Tile.key(1,0,-1));
    }

    private static Export scene(long epoch,long revision,float clearance) {
        return new Export(epoch,revision,List.of(new Value(0,64,0,revision,ShortBuffer.wrap(new short[]{(short)Math.round(clearance/3*65535)}),
                new long[]{-1,-1,-1,-1})),0);
    }
}
